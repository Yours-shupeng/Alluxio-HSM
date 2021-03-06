/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.hadoop;

import alluxio.Constants;
import alluxio.LocalAlluxioClusterResource;
import alluxio.PropertyKey;
import alluxio.security.authentication.AuthType;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.gcs.GCSUnderFileSystem;
import alluxio.underfs.hdfs.HdfsUnderFileSystem;
import alluxio.underfs.local.LocalUnderFileSystem;
import alluxio.underfs.oss.OSSUnderFileSystem;
import alluxio.underfs.s3.S3UnderFileSystem;
import alluxio.underfs.s3a.S3AUnderFileSystem;
import alluxio.underfs.swift.SwiftUnderFileSystem;
import alluxio.util.CommonUtils;
import alluxio.util.io.PathUtils;

import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Integration tests for {@link FileSystem#setOwner(Path, String, String)} and
 * {@link FileSystem#setPermission(Path, org.apache.hadoop.fs.permission.FsPermission)}.
 */
public final class FileSystemAclIntegrationTest {
  /**
   * The exception expected to be thrown.
   */
  @Rule
  public final ExpectedException mThrown = ExpectedException.none();

  private static final int BLOCK_SIZE = 1024;
  @ClassRule
  public static LocalAlluxioClusterResource sLocalAlluxioClusterResource =
      new LocalAlluxioClusterResource.Builder()
          .setProperty(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.SIMPLE.getAuthName())
          .setProperty(PropertyKey.SECURITY_AUTHORIZATION_PERMISSION_ENABLED, "true")
          .build();
  private static String sUfsRoot;
  private static UnderFileSystem sUfs;
  private static org.apache.hadoop.fs.FileSystem sTFS;

  private static void create(org.apache.hadoop.fs.FileSystem fs, Path path) throws IOException {
    FSDataOutputStream o = fs.create(path);
    o.writeBytes("Test Bytes");
    o.close();
  }

  public static void cleanup(org.apache.hadoop.fs.FileSystem fs) throws IOException {
    FileStatus[] statuses = fs.listStatus(new Path("/"));
    for (FileStatus f : statuses) {
      fs.delete(f.getPath(), true);
    }
  }

  @BeforeClass
  public static void beforeClass() throws Exception {
    Configuration conf = new Configuration();
    conf.set("fs.alluxio.impl", FileSystem.class.getName());

    URI uri = URI.create(sLocalAlluxioClusterResource.get().getMasterURI());

    sTFS = org.apache.hadoop.fs.FileSystem.get(uri, conf);
    sUfsRoot = PathUtils.concatPath(alluxio.Configuration.get(PropertyKey.UNDERFS_ADDRESS));
    sUfs = UnderFileSystem.get(sUfsRoot);
  }

  @After
  public void cleanupTFS() throws Exception {
    cleanup(sTFS);
  }

  @Test
  public void createFileWithPermission() throws Exception {
    List<Integer> permissionValues =
        Lists.newArrayList(0111, 0222, 0333, 0444, 0555, 0666, 0777, 0755, 0733, 0644, 0533, 0511);
    for (int value : permissionValues) {
      Path file = new Path("/createfile" + value);
      FsPermission permission = FsPermission.createImmutable((short) value);
      FSDataOutputStream o = sTFS.create(file, permission, false /* ignored */, 10 /* ignored */,
          (short) 1 /* ignored */, 512 /* ignored */, null /* ignored */);
      o.writeBytes("Test Bytes");
      o.close();
      FileStatus fs = sTFS.getFileStatus(file);
      Assert.assertEquals(permission, fs.getPermission());
    }
  }

  @Test
  public void mkdirsWithPermission() throws Exception {
    List<Integer> permissionValues =
        Lists.newArrayList(0111, 0222, 0333, 0444, 0555, 0666, 0777, 0755, 0733, 0644, 0533, 0511);
    for (int value : permissionValues) {
      Path dir = new Path("/createDir" + value);
      FsPermission permission = FsPermission.createImmutable((short) value);
      sTFS.mkdirs(dir, permission);
      FileStatus fs = sTFS.getFileStatus(dir);
      Assert.assertEquals(permission, fs.getPermission());
    }
  }

  /**
   * Test for {@link FileSystem#setPermission(Path, org.apache.hadoop.fs.permission.FsPermission)}.
   * It will test changing the permission of file using TFS.
   */
  @Test
  public void chmod() throws Exception {
    Path fileA = new Path("/chmodfileA");

    create(sTFS, fileA);
    FileStatus fs = sTFS.getFileStatus(fileA);
    Assert.assertTrue(sUfs.exists(PathUtils.concatPath(sUfsRoot, fileA)));
    // Default permission should be 0644
    Assert.assertEquals((short) 0644, fs.getPermission().toShort());

    sTFS.setPermission(fileA, FsPermission.createImmutable((short) 0755));
    Assert.assertEquals((short) 0755, sTFS.getFileStatus(fileA).getPermission().toShort());
  }

  /**
   * Test for {@link FileSystem#setOwner(Path, String, String)} with local UFS. It will test only
   * changing the owner of file using TFS and propagate the change to UFS. Since the arbitrary
   * owner does not exist in the local UFS, the operation would fail.
   */
  @Test
  public void changeNonexistentOwnerForLocal() throws Exception {
    if (!(sUfs instanceof LocalUnderFileSystem)) {
      // Skip non-local UFSs.
      return;
    }
    Path fileA = new Path("/chownfileA-local");
    final String nonexistentOwner = "nonexistent-user1";
    final String nonexistentGroup = "nonexistent-group1";

    create(sTFS, fileA);

    FileStatus fs = sTFS.getFileStatus(fileA);
    String defaultOwner = fs.getOwner();
    String defaultGroup = fs.getGroup();

    Assert.assertEquals(defaultOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertEquals(defaultGroup, sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileA)));

    Assert.assertNotEquals(defaultOwner, nonexistentOwner);
    Assert.assertNotEquals(defaultGroup, nonexistentGroup);

    // Expect a IOException for not able to setOwner for UFS with invalid owner name.
    mThrown.expect(IOException.class);
    mThrown.expectMessage("Could not setOwner for UFS file");
    sTFS.setOwner(fileA, nonexistentOwner, null);
  }

  /**
   * Test for {@link FileSystem#setOwner(Path, String, String)} with local UFS. It will test only
   * changing the group of file using TFS and propagate the change to UFS. Since the arbitrary
   * group does not exist in the local UFS, the operation would fail.
   */
  @Test
  public void changeNonexistentGroupForLocal() throws Exception {
    if (!(sUfs instanceof LocalUnderFileSystem)) {
      // Skip non-local UFSs.
      return;
    }
    Path fileB = new Path("/chownfileB-local");
    final String nonexistentOwner = "nonexistent-user1";
    final String nonexistentGroup = "nonexistent-group1";

    create(sTFS, fileB);

    FileStatus fs = sTFS.getFileStatus(fileB);
    String defaultOwner = fs.getOwner();
    String defaultGroup = fs.getGroup();

    Assert.assertEquals(defaultOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileB)));
    Assert.assertEquals(defaultGroup, sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileB)));

    Assert.assertNotEquals(defaultOwner, nonexistentOwner);
    Assert.assertNotEquals(defaultGroup, nonexistentGroup);

    // Expect a IOException for not able to setOwner for UFS with invalid group name.
    mThrown.expect(IOException.class);
    mThrown.expectMessage("Could not setOwner for UFS file");
    sTFS.setOwner(fileB, null, nonexistentGroup);
  }

  /**
   * Test for {@link FileSystem#setOwner(Path, String, String)} with local UFS. It will test
   * changing both owner and group of file using TFS and propagate the change to UFS. Since the
   * arbitrary owner and group do not exist in the local UFS, the operation would fail.
   */
  @Test
  public void changeNonexistentOwnerAndGroupForLocal() throws Exception {
    if (!(sUfs instanceof LocalUnderFileSystem)) {
      // Skip non-local UFSs.
      return;
    }
    Path fileC = new Path("/chownfileC-local");
    final String nonexistentOwner = "nonexistent-user1";
    final String nonexistentGroup = "nonexistent-group1";

    create(sTFS, fileC);

    FileStatus fs = sTFS.getFileStatus(fileC);
    String defaultOwner = fs.getOwner();
    String defaultGroup = fs.getGroup();

    Assert.assertEquals(defaultOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileC)));
    Assert.assertEquals(defaultGroup, sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileC)));

    Assert.assertNotEquals(defaultOwner, nonexistentOwner);
    Assert.assertNotEquals(defaultGroup, nonexistentGroup);

    mThrown.expect(IOException.class);
    mThrown.expectMessage("Could not setOwner for UFS file");
    sTFS.setOwner(fileC, nonexistentOwner, nonexistentGroup);
  }

  /**
   * Test for {@link FileSystem#setOwner(Path, String, String)} with HDFS UFS. It will test only
   * changing the owner of file using TFS and propagate the change to UFS.
   */
  @Test
  public void changeNonexistentOwnerForHdfs() throws Exception {
    if (!(sUfs instanceof HdfsUnderFileSystem)) {
      // Skip non-HDFS UFSs.
      return;
    }
    Path fileA = new Path("/chownfileA-hdfs");
    final String testOwner = "test-user1";
    final String testGroup = "test-group1";

    create(sTFS, fileA);

    FileStatus fs = sTFS.getFileStatus(fileA);
    String defaultOwner = fs.getOwner();
    String defaultGroup = fs.getGroup();

    Assert.assertEquals(defaultOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileA)));
    // Group can different because HDFS user to group mapping can be different from that in Alluxio.

    Assert.assertNotEquals(defaultOwner, testOwner);
    Assert.assertNotEquals(defaultGroup, testGroup);

    // Expect a IOException for not able to setOwner for UFS with invalid owner name.
    sTFS.setOwner(fileA, testOwner, null);

    fs = sTFS.getFileStatus(fileA);
    Assert.assertEquals(testOwner, fs.getOwner());
    Assert.assertEquals(defaultGroup, fs.getGroup());
    Assert.assertEquals(testOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertEquals(defaultGroup, sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileA)));
  }

  /**
   * Test for {@link FileSystem#setOwner(Path, String, String)} with HDFS UFS. It will test only
   * changing the group of file using TFS and propagate the change to UFS.
   */
  @Test
  public void changeNonexistentGroupForHdfs() throws Exception {
    if (!(sUfs instanceof HdfsUnderFileSystem)) {
      // Skip non-HDFS UFSs.
      return;
    }
    Path fileB = new Path("/chownfileB-hdfs");
    final String testOwner = "test-user1";
    final String testGroup = "test-group1";

    create(sTFS, fileB);

    FileStatus fs = sTFS.getFileStatus(fileB);
    String defaultOwner = fs.getOwner();
    String defaultGroup = fs.getGroup();

    Assert.assertEquals(defaultOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileB)));
    // Group can different because HDFS user to group mapping can be different from that in Alluxio.

    Assert.assertNotEquals(defaultOwner, testOwner);
    Assert.assertNotEquals(defaultGroup, testGroup);

    sTFS.setOwner(fileB, null, testGroup);
    fs = sTFS.getFileStatus(fileB);
    Assert.assertEquals(defaultOwner, fs.getOwner());
    Assert.assertEquals(testGroup, fs.getGroup());
    Assert.assertEquals(defaultOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileB)));
    Assert.assertEquals(testGroup, sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileB)));
  }

  /**
   * Test for {@link FileSystem#setOwner(Path, String, String)} with HDFS UFS. It will test
   * changing both owner and group of file using TFS and propagate the change to UFS.
   */
  @Test
  public void changeNonexistentOwnerAndGroupForHdfs() throws Exception {
    if (!(sUfs instanceof HdfsUnderFileSystem)) {
      // Skip non-HDFS UFSs.
      return;
    }
    Path fileC = new Path("/chownfileC-hdfs");
    final String testOwner = "test-user1";
    final String testGroup = "test-group1";

    create(sTFS, fileC);

    FileStatus fs = sTFS.getFileStatus(fileC);
    String defaultOwner = fs.getOwner();
    String defaultGroup = fs.getGroup();

    Assert.assertEquals(defaultOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileC)));
    // Group can different because HDFS user to group mapping can be different from that in Alluxio.

    Assert.assertNotEquals(defaultOwner, testOwner);
    Assert.assertNotEquals(defaultGroup, testGroup);

    sTFS.setOwner(fileC, testOwner, testGroup);
    fs = sTFS.getFileStatus(fileC);
    Assert.assertEquals(testOwner, fs.getOwner());
    Assert.assertEquals(testGroup, fs.getGroup());
    Assert.assertEquals(testOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileC)));
    Assert.assertEquals(testGroup, sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileC)));
  }

  /**
   * Test for {@link FileSystem#setOwner(Path, String, String)}. It will test both owner and group
   * are null.
   */
  @Test
  public void checkNullOwnerAndGroup() throws Exception {
    Path fileD = new Path("/chownfileD");

    create(sTFS, fileD);

    FileStatus fs = sTFS.getFileStatus(fileD);
    String defaultOwner = fs.getOwner();
    String defaultGroup = fs.getGroup();

    sTFS.setOwner(fileD, null, null);

    fs = sTFS.getFileStatus(fileD);
    Assert.assertEquals(defaultOwner, fs.getOwner());
    Assert.assertEquals(defaultGroup, fs.getGroup());
  }

  /**
   * Tests the directory permission propagation to UFS.
   */
  @Test
  public void directoryPermissionForUfs() throws IOException {
    if (!(sUfs instanceof LocalUnderFileSystem) && !(sUfs instanceof HdfsUnderFileSystem)) {
      // Skip non-local and non-HDFS UFSs.
      return;
    }
    Path dir = new Path("/root/dir/");
    sTFS.mkdirs(dir);

    FileStatus fs = sTFS.getFileStatus(dir);
    String defaultOwner = fs.getOwner();
    Short dirMode = fs.getPermission().toShort();
    FileStatus parentFs = sTFS.getFileStatus(dir.getParent());
    Short parentMode = parentFs.getPermission().toShort();

    Assert.assertEquals(defaultOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, dir)));
    Assert.assertEquals((int) dirMode,
        (int) sUfs.getMode(PathUtils.concatPath(sUfsRoot, dir)));
    Assert.assertEquals((int) parentMode,
        (int) sUfs.getMode(PathUtils.concatPath(sUfsRoot, dir.getParent())));

    short newMode = (short) 0755;
    FsPermission newPermission = new FsPermission(newMode);
    sTFS.setPermission(dir, newPermission);

    Assert.assertEquals((int) newMode,
        (int) sUfs.getMode(PathUtils.concatPath(sUfsRoot, dir)));
  }

  /**
   * Tests the parent directory permission when mkdirs recursively.
   */
  @Test
  public void parentDirectoryPermissionForUfs() throws IOException {
    if (!(sUfs instanceof LocalUnderFileSystem) && !(sUfs instanceof HdfsUnderFileSystem)) {
      // Skip non-local and non-HDFS UFSs.
      return;
    }
    Path fileA = new Path("/root/dirA/fileA");
    Path dirA = fileA.getParent();
    sTFS.mkdirs(dirA);
    short parentMode = (short) 0700;
    FsPermission newPermission = new FsPermission(parentMode);
    sTFS.setPermission(dirA, newPermission);

    create(sTFS, fileA);

    Assert.assertEquals((int) parentMode,
        (int) sUfs.getMode(PathUtils.concatPath(sUfsRoot, dirA)));

    // Rename from dirA to dirB, file and its parent permission should be in sync with the source
    // dirA.
    Path fileB = new Path("/root/dirB/fileB");
    Path dirB = fileB.getParent();
    sTFS.rename(dirA, dirB);
    Assert.assertEquals((int) parentMode,
        (int) sUfs.getMode(PathUtils.concatPath(sUfsRoot, fileB.getParent())));
  }

  /**
   * Tests the loaded file metadata from UFS having the same mode as that in the UFS.
   */
  @Test
  public void loadFileMetadataMode() throws Exception {
    if (!(sUfs instanceof LocalUnderFileSystem) && !(sUfs instanceof HdfsUnderFileSystem)) {
      // Skip non-local and non-HDFS UFSs.
      return;
    }
    List<Integer> permissionValues =
        Lists.newArrayList(0111, 0222, 0333, 0444, 0555, 0666, 0777, 0755, 0733, 0644, 0533, 0511);

    for (int value : permissionValues) {
      Path file = new Path("/loadFileMetadataMode" + value);
      // Create a file directly in UFS and set the corresponding mode.
      String ufsPath = PathUtils.concatPath(sUfsRoot, file);
      sUfs.create(ufsPath).close();
      sUfs.setMode(ufsPath, (short) value);
      Assert.assertTrue(sUfs.exists(PathUtils.concatPath(sUfsRoot, file)));
      // Check the mode is consistent in Alluxio namespace once it's loaded from UFS to Alluxio.
      Assert.assertEquals((short) value, sTFS.getFileStatus(file).getPermission().toShort());
    }
  }

  /**
   * Tests the loaded directory metadata from UFS having the same mode as that in the UFS.
   */
  @Test
  public void loadDirMetadataMode() throws Exception {
    if (!(sUfs instanceof LocalUnderFileSystem) && !(sUfs instanceof HdfsUnderFileSystem)) {
      // Skip non-local and non-HDFS UFSs.
      return;
    }
    List<Integer> permissionValues =
        Lists.newArrayList(0111, 0222, 0333, 0444, 0555, 0666, 0777, 0755, 0733, 0644, 0533, 0511);

    for (int value : permissionValues) {
      Path file = new Path("/loadDirMetadataMode" + value);
      // Create a directory directly in UFS and set the corresponding mode.
      String ufsPath = PathUtils.concatPath(sUfsRoot, file);
      sUfs.mkdirs(ufsPath, false);
      sUfs.setMode(ufsPath, (short) value);
      Assert.assertTrue(sUfs.exists(PathUtils.concatPath(sUfsRoot, file)));
      // Check the mode is consistent in Alluxio namespace once it's loaded from UFS to Alluxio.
      Assert.assertEquals((short) value, sTFS.getFileStatus(file).getPermission().toShort());
    }
  }

  @Test
  public void s3GetPermission() throws Exception {
    Assume.assumeTrue((sUfs instanceof S3UnderFileSystem) || (sUfs instanceof S3AUnderFileSystem));

    alluxio.Configuration.set(PropertyKey.UNDERFS_S3_OWNER_ID_TO_USERNAME_MAPPING, "");
    Path fileA = new Path("/objectfileA");
    create(sTFS, fileA);
    Assert.assertTrue(sUfs.exists(PathUtils.concatPath(sUfsRoot, fileA)));

    // Without providing "alluxio.underfs.s3.canonical.owner.id.to.username.mapping", the default
    // display name of the S3 owner account is NOT empty.
    Assert.assertNotEquals("", sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertNotEquals("", sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertEquals((short) 0700, sUfs.getMode(PathUtils.concatPath(sUfsRoot, fileA)));
  }

  @Test
  public void gcsGetPermission() throws Exception {
    Assume.assumeTrue(sUfs instanceof GCSUnderFileSystem);

    alluxio.Configuration.set(PropertyKey.UNDERFS_GCS_OWNER_ID_TO_USERNAME_MAPPING, "");
    Path fileA = new Path("/objectfileA");
    create(sTFS, fileA);
    Assert.assertTrue(sUfs.exists(PathUtils.concatPath(sUfsRoot, fileA)));

    // Without providing "alluxio.underfs.gcs.owner.id.to.username.mapping", the default
    // display name of the GCS owner account is empty. The owner will be the GCS account id, which
    // is not empty.
    Assert.assertNotEquals("", sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertNotEquals("", sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertEquals((short) 0700, sUfs.getMode(PathUtils.concatPath(sUfsRoot, fileA)));
  }

  @Test
  public void swiftGetPermission() throws Exception {
    Assume.assumeTrue(sUfs instanceof SwiftUnderFileSystem);

    Path fileA = new Path("/objectfileA");
    create(sTFS, fileA);
    Assert.assertTrue(sUfs.exists(PathUtils.concatPath(sUfsRoot, fileA)));

    Assert.assertNotEquals("", sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertNotEquals("", sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertEquals((short) 0700, sUfs.getMode(PathUtils.concatPath(sUfsRoot, fileA)));
  }

  @Test
  public void ossGetPermission() throws Exception {
    Assume.assumeTrue(sUfs instanceof OSSUnderFileSystem);

    Path fileA = new Path("/objectfileA");
    create(sTFS, fileA);
    Assert.assertTrue(sUfs.exists(PathUtils.concatPath(sUfsRoot, fileA)));

    // Verify the owner, group and permission of OSS UFS is not supported and thus returns default
    // values.
    Assert.assertEquals("", sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertEquals("", sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertEquals(Constants.DEFAULT_FILE_SYSTEM_MODE,
        sUfs.getMode(PathUtils.concatPath(sUfsRoot, fileA)));
  }

  @Test
  public void objectStoreSetOwner() throws Exception {
    Assume.assumeTrue(CommonUtils.isUfsObjectStorage(sUfsRoot));

    Path fileA = new Path("/objectfileA");
    final String newOwner = "new-user1";
    final String newGroup = "new-group1";
    create(sTFS, fileA);

    // Set owner to Alluxio files that are persisted in UFS will NOT propagate to underlying object.
    sTFS.setOwner(fileA, newOwner, newGroup);
    Assert.assertNotEquals(newOwner, sUfs.getOwner(PathUtils.concatPath(sUfsRoot, fileA)));
    Assert.assertNotEquals(newGroup, sUfs.getGroup(PathUtils.concatPath(sUfsRoot, fileA)));
  }
}
