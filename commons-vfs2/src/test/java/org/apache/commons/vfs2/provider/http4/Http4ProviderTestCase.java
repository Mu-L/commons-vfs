/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.vfs2.provider.http4;

import static org.apache.commons.vfs2.VfsTestUtils.getTestDirectory;

import java.io.File;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.commons.vfs2.AbstractProviderTestConfig;
import org.apache.commons.vfs2.CacheStrategy;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileNotFolderException;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.ProviderTestSuite;
import org.apache.commons.vfs2.VFS;
import org.apache.commons.vfs2.cache.SoftRefFilesCache;
import org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.apache.commons.vfs2.util.NHttpFileServer;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;

/**
 * Test cases for the HTTP4 provider.
 */
public class Http4ProviderTestCase extends AbstractProviderTestConfig {

    private static NHttpFileServer server;

    private static final String TEST_URI = "test.http.uri";

    /**
     * Use %40 for @ in URLs
     */
    private static String connectionUri;

    private static String getSystemTestUriOverride() {
        return System.getProperty(TEST_URI);
    }

    /**
     * Creates and starts an embedded Apache HTTP Server (HttpComponents).
     *
     * @throws Exception
     */
    private static void setUpClass() throws Exception {
        server = NHttpFileServer.start(0, new File(getTestDirectory()), 5000);
        final int socketPort = server.getPort();
        connectionUri = getLocalHostUriString("http4", server.getPort());
    }

    /**
     * Creates a new test suite.
     *
     * @return a new test suite.
     * @throws Exception Thrown when the suite cannot be constructed.
     */
    public static junit.framework.Test suite() throws Exception {
        return new ProviderTestSuite(new Http4ProviderTestCase()) {
            /**
             * Adds base tests - excludes the nested test cases.
             */
            @Override
            protected void addBaseTests() throws Exception {
                super.addBaseTests();

                addTests(Http4ProviderTestCase.class);

                // HttpAsyncServer returns 400 on link local requests from Httpclient
                // (e.g. Apache Web Server does the same https://bz.apache.org/bugzilla/show_bug.cgi?id=35122,
                // but not every HTTP server does).
                // Until this is addressed, local connection test won't work end-to-end

                // if (getSystemTestUriOverride() == null) {
                //    addTests(IPv6LocalConnectionTests.class);
                // }
            }

            @Override
            protected void setUp() throws Exception {
                if (getSystemTestUriOverride() == null) {
                    setUpClass();
                }
                super.setUp();
            }

            @Override
            protected void tearDown() throws Exception {
                tearDownClass();
                super.tearDown();
            }
        };
    }

    /**
     * Stops the embedded Apache HTTP Server.
     * @throws InterruptedException
     */
    private static void tearDownClass() throws InterruptedException {
        if (server != null) {
            server.shutdown(5000, TimeUnit.SECONDS);
        }
    }

    private void checkReadTestsFolder(final FileObject file) throws FileSystemException {
        Assertions.assertNotNull(file.getChildren());
        Assertions.assertTrue(file.getChildren().length > 0);
    }

    /**
     * Returns the base folder for tests.
     */
    @Override
    public FileObject getBaseTestFolder(final FileSystemManager manager) throws Exception {
        String uri = getSystemTestUriOverride();
        if (uri == null) {
            uri = connectionUri;
        }
        return manager.resolveFile(uri);
    }

    // Test no longer passing 2016/04/28
    public void ignoreTestHttp405() throws FileSystemException {
        @SuppressWarnings("resource") // getManager() returns a global.
        final FileObject fileObject = VFS.getManager()
                .resolveFile("http4://www.w3schools.com/webservices/tempconvert.asmx?action=WSDL");
        Assertions.assertFalse(fileObject.getContent().isEmpty(), "Content should not be empty");
    }

    /**
     * Prepares the file system manager.
     */
    @Override
    public void prepare(final DefaultFileSystemManager manager) throws Exception {
        if (!manager.hasProvider("http4")) {
            manager.addProvider("http4", new Http4FileProvider());
        }
    }

    /** Ensure VFS-453 options are present. */
    @SuppressWarnings("deprecation")
    @Test
    public void testHttpTimeoutConfig() {
        final FileSystemOptions opts = new FileSystemOptions();
        final Http4FileSystemConfigBuilder builder = Http4FileSystemConfigBuilder.getInstance();

        // ensure defaults are 0
        assertEquals(0, builder.getConnectionTimeout(opts));
        assertEquals(Duration.ZERO, builder.getConnectionTimeoutDuration(opts));
        assertEquals(0, builder.getSoTimeout(opts));
        assertEquals(Duration.ZERO, builder.getSoTimeoutDuration(opts));
        assertEquals("Jakarta-Commons-VFS", builder.getUserAgent(opts));

        // Set int timeouts
        builder.setConnectionTimeout(opts, 60000);
        builder.setSoTimeout(opts, 60000);
        builder.setUserAgent(opts, "foo/bar");

        // ensure changes are visible
        assertEquals(60_000, builder.getConnectionTimeout(opts));
        assertEquals(60_000, builder.getConnectionTimeoutDuration(opts).toMillis());
        assertEquals(60_000, builder.getSoTimeout(opts));
        assertEquals(60_000, builder.getSoTimeoutDuration(opts).toMillis());
        assertEquals("foo/bar", builder.getUserAgent(opts));

        // Set Duration timeouts
        builder.setConnectionTimeout(opts, Duration.ofMinutes(1));
        builder.setSoTimeout(opts, Duration.ofMinutes(1));
        builder.setUserAgent(opts, "foo/bar");

        // ensure changes are visible
        assertEquals(60_000, builder.getConnectionTimeout(opts));
        assertEquals(60_000, builder.getConnectionTimeoutDuration(opts).toMillis());
        assertEquals(60_000, builder.getSoTimeout(opts));
        assertEquals(60_000, builder.getSoTimeoutDuration(opts).toMillis());
        assertEquals("foo/bar", builder.getUserAgent(opts));
    }

    @Test
    public void testReadFileOperations() throws Exception {
        try (DefaultFileSystemManager manager = new DefaultFileSystemManager();
                Http4FileProvider provider = new Http4FileProvider();
                SoftRefFilesCache filesCache = new SoftRefFilesCache();) {
            manager.addProvider("http4", provider);
            manager.setFilesCache(filesCache);
            manager.setCacheStrategy(CacheStrategy.ON_RESOLVE);
            final String nonExistentFileUri = connectionUri + "/read-tests/nonexistent.txt";
            try (FileObject nonExistentFileObject = manager.resolveFile(nonExistentFileUri); FileContent content = nonExistentFileObject.getContent();) {
                // Attempt to read from the stream
                final FileSystemException e = Assertions.assertThrows(FileSystemException.class, content::getInputStream);
                Assertions.assertTrue(e.getCode().contains("read-not-file.error"),
                        "Expected HTTP 404 Not Found error, but got: " + e.getMessage() + " " + e.getCode());
            }
            final String existentFileUri = connectionUri + "/read-tests/file1.txt";
            try (FileObject existentFileObject = manager.resolveFile(existentFileUri)) {
                Assertions.assertTrue(existentFileObject.exists(), "File should exist");
                try (FileContent content = existentFileObject.getContent(); InputStream inputStream = content.getInputStream()) {
                    Assertions.assertNotNull(inputStream, "InputStream should not be null");
                    final int available = inputStream.available();
                    Assertions.assertTrue(available > 0, "InputStream should have content available");
                    final byte[] buffer = new byte[1024];
                    final int bytesRead = inputStream.read(buffer);
                    Assertions.assertTrue(bytesRead > 0, "InputStream should read non-empty content");
                }
            }
        }
    }

    private void testResolveFolderSlash(final String uri, final boolean followRedirect) throws FileSystemException {
        VFS.getManager().getFilesCache().close();
        final FileSystemOptions opts = new FileSystemOptions();
        Http4FileSystemConfigBuilder.getInstance().setFollowRedirect(opts, followRedirect);
        @SuppressWarnings("resource") // getManager() returns a global.
        final FileObject file = VFS.getManager().resolveFile(uri, opts);
        try {
            checkReadTestsFolder(file);
        } catch (final FileNotFolderException e) {
            // Expected: VFS HTTP does not support listing children yet.
        }
    }

    @Test
    public void testResolveFolderSlashNoRedirectOff() throws FileSystemException {
        testResolveFolderSlash(connectionUri + "/read-tests", false);
    }

    @Test
    public void testResolveFolderSlashNoRedirectOn() throws FileSystemException {
        testResolveFolderSlash(connectionUri + "/read-tests", true);
    }

    @Test
    public void testResolveFolderSlashYesRedirectOff() throws FileSystemException {
        testResolveFolderSlash(connectionUri + "/read-tests/", false);
    }

    @Test
    public void testResolveFolderSlashYesRedirectOn() throws FileSystemException {
        testResolveFolderSlash(connectionUri + "/read-tests/", true);
    }

    @Test
    public void testResolveIPv6Url() throws FileSystemException {
        final String ipv6Url = "http4://[fe80::1c42:dae:8370:aea6%en1]";

        @SuppressWarnings("rawtypes")
        final Http4FileObject fileObject = (Http4FileObject)
                VFS.getManager().resolveFile(ipv6Url, new FileSystemOptions());

        assertEquals("http://[fe80::1c42:dae:8370:aea6%en1]/", fileObject.getInternalURI().toString());
    }
}
