package org.apache.hadoop.hdfs.server.namenode;

import ai.sapper.hcdc.agents.namenode.NameNodeEnv;
import ai.sapper.hcdc.agents.namenode.NameNodeError;
import ai.sapper.hcdc.common.ConfigReader;
import ai.sapper.hcdc.common.utils.DefaultLogger;
import ai.sapper.hcdc.core.connections.HdfsConnection;
import ai.sapper.hcdc.core.connections.ZookeeperConnection;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdfs.tools.offlineImageViewer.OfflineImageViewer;
import org.apache.hadoop.hdfs.tools.offlineImageViewer.PBImageXmlWriter;
import org.apache.hadoop.hdfs.tools.offlineImageViewer.XmlImageVisitor;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Accessors(fluent = true)
public class NameNodeReplicator {
    private static class Constants {
        private static final String NODE_INODES = "INodeSection.inode";
        private static final String NODE_TX_ID = "NameSection.txid";
        private static final String NODE_DIR_SECTION = "INodeDirectorySection";
        private static final String NODE_DIR_NODE = String.format("%s.directory", NODE_DIR_SECTION);
    }

    private ZookeeperConnection zkConnection;
    private HdfsConnection hdfsConnection;
    private FileSystem fs;
    private HierarchicalConfiguration<ImmutableNode> config;
    private ReplicatorConfig replicatorConfig;
    private ZkStateManager stateManager;

    @Parameter(names = {"--image", "-i"}, required = true, description = "Path to the FS Image file.")
    private String fsImageFile;
    @Parameter(names = {"--config", "-c"}, required = true, description = "Path to the configuration file.")
    private String configfile;
    @Parameter(names = {"--tmp", "-t"}, description = "Temp directory to use to create local files. [DEFAULT=System.getProperty(\"java.io.tmpdir\")]")
    private String tempDir = System.getProperty("java.io.tmpdir");

    private long txnId;
    private final Map<Long, DFSInode> inodes = new HashMap<>();
    private final Map<Long, DFSDirectory> directoryMap = new HashMap<>();

    public void init() throws NameNodeError {
        try {
            config = ConfigReader.read(configfile);
            NameNodeEnv.setup(config);
            replicatorConfig = new ReplicatorConfig(config);
            replicatorConfig.read();

            hdfsConnection = NameNodeEnv.connectionManager().getConnection(replicatorConfig().hdfsConnection(), HdfsConnection.class);
            Preconditions.checkNotNull(hdfsConnection);
            hdfsConnection.connect();

            zkConnection = NameNodeEnv.connectionManager().getConnection(replicatorConfig().zkConnection(), ZookeeperConnection.class);
            Preconditions.checkNotNull(zkConnection);
            zkConnection.connect();

            fs = hdfsConnection.fileSystem();
            stateManager = NameNodeEnv.stateManager();
            Preconditions.checkNotNull(stateManager);

        } catch (Throwable t) {
            DefaultLogger.__LOG.error(t.getLocalizedMessage());
            DefaultLogger.__LOG.debug(DefaultLogger.stacktrace(t));
            throw new NameNodeError(t);
        }
    }


    public void run() throws NameNodeError {
        try {
            NameNodeEnv.globalLock().lock();
            try {
                String output = generateFSImageSnapshot();
                DefaultLogger.__LOG.info(String.format("Generated FS Image XML. [path=%s]", output));
                readFSImageXml(output);
            } finally {
                NameNodeEnv.globalLock().unlock();
            }
        } catch (Throwable t) {
            DefaultLogger.__LOG.error(t.getLocalizedMessage());
            DefaultLogger.__LOG.debug(DefaultLogger.stacktrace(t));
            throw new NameNodeError(t);
        }
    }

    private void readFSImageXml(String file) throws Exception {
        HierarchicalConfiguration<ImmutableNode> rootNode = ConfigReader.read(file);
        String s = rootNode.getString(Constants.NODE_TX_ID);
        if (Strings.isNullOrEmpty(s)) {
            throw new NameNodeError(String.format("NameNode Last Transaction ID not found. [file=%s]", file));
        }
        txnId = Long.parseLong(s);

        List<HierarchicalConfiguration<ImmutableNode>> nodes = rootNode.configurationsAt(Constants.NODE_INODES);
        if (nodes != null && !nodes.isEmpty()) {
            for (HierarchicalConfiguration<ImmutableNode> node : nodes) {
                DFSInode inode = readInode(node);
                if (inode != null) {
                    inodes.put(inode.id, inode);
                }
            }
        }
        List<HierarchicalConfiguration<ImmutableNode>> dnodes = rootNode.configurationsAt(Constants.NODE_DIR_NODE);
        if (dnodes != null && !dnodes.isEmpty()) {
            for (HierarchicalConfiguration<ImmutableNode> node : dnodes) {
                DFSDirectory dir = new DFSDirectory().read(node);
                directoryMap.put(dir.id, dir);
            }
        }
    }

    private DFSInode readInode(HierarchicalConfiguration<ImmutableNode> node) throws Exception {
        return new DFSInode().read(node);
    }

    private String generateFSImageSnapshot() throws Exception {
        File fsImage = new File(fsImageFile);
        Preconditions.checkState(fsImage.exists());

        String output = String.format("%s/%s.xml", tempDir, fsImage.getName());
        Configuration conf = new Configuration();
        try (PrintStream out = new PrintStream(output)) {
            new PBImageXmlWriter(conf, out).visit(new RandomAccessFile(fsImage.getAbsolutePath(),
                    "r"));
        }

        return output;
    }

    @Getter
    @Accessors(fluent = true)
    public static class ReplicatorConfig extends ConfigReader {
        private static final String __CONFIG_PATH = "replicator";
        private static final String CONFIG_CONNECTION_ZK = "connections.zk";
        private static final String CONFIG_CONNECTION_HDFS = "connections.hdfs";
        private static final String CONFIG_ZK_BASE_PATH = "basePath";

        private String zkConnection;
        private String hdfsConnection;
        private String zkBasePath;

        public ReplicatorConfig(@NonNull HierarchicalConfiguration<ImmutableNode> config) {
            super(config, __CONFIG_PATH);
        }

        public void read() throws ConfigurationException {
            zkConnection = get().getString(CONFIG_CONNECTION_ZK);
            if (Strings.isNullOrEmpty(zkConnection)) {
                throw new ConfigurationException(String.format("NameNode Replicator Configuration Error: missing [%s]", CONFIG_CONNECTION_ZK));
            }

            hdfsConnection = get().getString(CONFIG_CONNECTION_HDFS);
            if (Strings.isNullOrEmpty(hdfsConnection)) {
                throw new ConfigurationException(String.format("NameNode Replicator Configuration Error: missing [%s]", CONFIG_CONNECTION_HDFS));
            }

            zkBasePath = get().getString(CONFIG_ZK_BASE_PATH);
            if (Strings.isNullOrEmpty(zkBasePath)) {
                throw new ConfigurationException(String.format("NameNode Replicator Configuration Error: missing [%s]", CONFIG_ZK_BASE_PATH));
            }
        }
    }

    public static void main(String[] args) {
        try {
            NameNodeReplicator replicator = new NameNodeReplicator();
            JCommander.newBuilder().addObject(replicator).build().parse(args);
            replicator.init();
            replicator.run();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public enum EInodeType {
        FILE, DIRECTORY, SYMLINK
    }

    @Getter
    @Setter
    @Accessors(fluent = true)
    public static class DFSInode {
        private static final String NODE_INODE_ID = "id";
        private static final String NODE_INODE_TYPE = "type";
        private static final String NODE_INODE_NAME = "name";
        private static final String NODE_INODE_MTIME = "mtime";
        private static final String NODE_INODE_ATIME = "atime";
        private static final String NODE_INODE_BS = "preferredBlockSize";
        private static final String NODE_INODE_PERM = "permission";

        private static final String NODE_INODE_BLOCKS = "blocks";
        private static final String NODE_INODE_BLOCK = String.format("%s.block", NODE_INODE_BLOCKS);

        private long id;
        private EInodeType type;
        private String name;
        private long mTime;
        private long aTime;
        private long preferredBlockSize;
        private String user;
        private String group;

        private List<DFSInodeBlock> blocks;

        public DFSInode read(@NonNull HierarchicalConfiguration<ImmutableNode> node) throws Exception {
            id = node.getLong(NODE_INODE_ID);
            String s = node.getString(NODE_INODE_TYPE);
            if (Strings.isNullOrEmpty(s)) {
                throw new Exception(String.format("Missing Inode field. [field=%s]", NODE_INODE_TYPE));
            }
            type = EInodeType.valueOf(s);
            name = node.getString(NODE_INODE_NAME);
            if (node.containsKey(NODE_INODE_MTIME)) {
                mTime = node.getLong(NODE_INODE_MTIME);
            }
            if (node.containsKey(NODE_INODE_ATIME)) {
                aTime = node.getLong(NODE_INODE_ATIME);
            }
            if (node.containsKey(NODE_INODE_BS)) {
                preferredBlockSize = node.getLong(NODE_INODE_BS);
            }
            s = node.getString(NODE_INODE_PERM);
            if (!Strings.isNullOrEmpty(s)) {
                String[] parts = s.split(":");
                if (parts.length == 3) {
                    user = parts[0];
                    group = parts[1];
                }
            }
            List<HierarchicalConfiguration<ImmutableNode>> nodes = node.configurationsAt(NODE_INODE_BLOCK);
            if (nodes != null && !nodes.isEmpty()) {
                blocks = new ArrayList<>();
                for (HierarchicalConfiguration<ImmutableNode> nn : nodes) {
                    DFSInodeBlock block = new DFSInodeBlock().read(nn);
                    blocks.add(block);
                }
            }
            return this;
        }
    }

    @Getter
    @Setter
    @Accessors(fluent = true)
    public static class DFSInodeBlock {
        private static final String NODE_BLOCK_ID = "id";
        private static final String NODE_BLOCK_NB = "numBytes";
        private static final String NODE_BLOCK_GEN = "genstamp";

        private long id;
        private long numBytes;
        private long genStamp;

        public DFSInodeBlock read(@NonNull HierarchicalConfiguration<ImmutableNode> node) throws Exception {
            id = node.getLong(NODE_BLOCK_ID);
            numBytes = node.getLong(NODE_BLOCK_NB);
            genStamp = node.getLong(NODE_BLOCK_GEN);

            return this;
        }
    }

    @Getter
    @Setter
    @Accessors(fluent = true)
    public static class DFSDirectory {
        private static final String NODE_DIR_ID = "parent";
        private static final String NODE_DIR_CHILD = "child";

        private long id;
        private List<Long> children;

        public DFSDirectory read(@NonNull HierarchicalConfiguration<ImmutableNode> node) throws Exception {
            id = node.getLong(NODE_DIR_ID);
            if (node.containsKey(NODE_DIR_CHILD)) {
                children = node.getList(Long.class, NODE_DIR_CHILD);
            }
            return this;
        }
    }
}
