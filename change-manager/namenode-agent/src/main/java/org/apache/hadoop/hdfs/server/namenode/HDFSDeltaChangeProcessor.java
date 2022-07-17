package org.apache.hadoop.hdfs.server.namenode;

import ai.sapper.hcdc.agents.namenode.NameNodeEnv;
import ai.sapper.hcdc.agents.namenode.model.DFSReplicationState;
import ai.sapper.hcdc.agents.namenode.model.NameNodeTxState;
import ai.sapper.hcdc.common.ConfigReader;
import ai.sapper.hcdc.common.model.*;
import ai.sapper.hcdc.common.utils.DefaultLogger;
import ai.sapper.hcdc.core.connections.ConnectionManager;
import ai.sapper.hcdc.core.connections.state.DFSBlockState;
import ai.sapper.hcdc.core.connections.state.DFSFileState;
import ai.sapper.hcdc.core.messaging.*;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Getter
@Accessors(fluent = true)
public class HDFSDeltaChangeProcessor implements Runnable {
    private static Logger LOG = LoggerFactory.getLogger(HDFSDeltaChangeProcessor.class.getCanonicalName());

    private final ZkStateManager stateManager;
    private HDFSDeltaChangeProcessorConfig processorConfig;
    private MessageSender<String, DFSChangeDelta> sender;
    private MessageSender<String, DFSChangeDelta> errorSender;
    private MessageReceiver<String, DFSChangeDelta> receiver;
    private long receiveBatchTimeout = 1000;

    public HDFSDeltaChangeProcessor(@NonNull ZkStateManager stateManager) {
        this.stateManager = stateManager;
    }

    public HDFSDeltaChangeProcessor init(@NonNull HierarchicalConfiguration<ImmutableNode> xmlConfig,
                                         @NonNull ConnectionManager manger) throws ConfigurationException {
        try {
            processorConfig = new HDFSDeltaChangeProcessorConfig(xmlConfig);
            processorConfig.read();

            sender = new HCDCMessagingBuilders.SenderBuilder()
                    .config(processorConfig.senderConfig.config())
                    .manager(manger)
                    .connection(processorConfig().senderConfig.connection())
                    .type(processorConfig().senderConfig.type())
                    .partitioner(processorConfig().senderConfig.partitionerClass())
                    .topic(processorConfig().senderConfig.topic())
                    .build();

            receiver = new HCDCMessagingBuilders.ReceiverBuilder()
                    .config(processorConfig().receiverConfig.config())
                    .manager(manger)
                    .connection(processorConfig.receiverConfig.connection())
                    .type(processorConfig.receiverConfig.type())
                    .topic(processorConfig.receiverConfig.topic())
                    .build();

            if (!Strings.isNullOrEmpty(processorConfig.batchTimeout)) {
                receiveBatchTimeout = Long.parseLong(processorConfig.batchTimeout);
            }
            errorSender = new HCDCMessagingBuilders.SenderBuilder()
                    .config(processorConfig.errorConfig.config())
                    .manager(manger)
                    .connection(processorConfig().errorConfig.connection())
                    .type(processorConfig().errorConfig.type())
                    .partitioner(processorConfig().errorConfig.partitionerClass())
                    .topic(processorConfig().errorConfig.topic())
                    .build();

            return this;
        } catch (Exception ex) {
            throw new ConfigurationException(ex);
        }
    }

    /**
     * When an object implementing interface <code>Runnable</code> is used
     * to create a thread, starting the thread causes the object's
     * <code>run</code> method to be called in that separately executing
     * thread.
     * <p>
     * The general contract of the method <code>run</code> is that it may
     * take any action whatsoever.
     *
     * @see Thread#run()
     */
    @Override
    public void run() {
        Preconditions.checkState(sender != null);
        Preconditions.checkState(receiver != null);
        Preconditions.checkState(errorSender != null);
        try {
            while (NameNodeEnv.get().state().isAvailable()) {
                List<MessageObject<String, DFSChangeDelta>> batch = receiver.nextBatch(receiveBatchTimeout);
                if (batch == null || batch.isEmpty()) {
                    Thread.sleep(receiveBatchTimeout);
                    continue;
                }
                LOG.debug(String.format("Received messages. [count=%d]", batch.size()));
                for (MessageObject<String, DFSChangeDelta> message : batch) {
                    try {
                        long txId = process(message);
                        if (txId > 0) {
                            stateManager.update(txId);
                            LOG.debug(String.format("Processed transaction delta. [TXID=%d]", txId));
                        }
                    } catch (InvalidMessageError ie) {
                        LOG.error("Error processing message.", ie);
                        DefaultLogger.stacktrace(LOG, ie);
                        errorSender.send(message);
                    }
                    receiver.ack(message.id());
                }
            }
            LOG.warn(String.format("Delta Change Processor thread stopped. [env state=%s]", NameNodeEnv.get().state().state().name()));
        } catch (Throwable t) {
            LOG.error("Delta Change Processor terminated with error", t);
            DefaultLogger.stacktrace(LOG, t);
        }
    }

    private long process(MessageObject<String, DFSChangeDelta> message) throws Exception {
        long txId = -1;
        if (!isValidMessage(message)) {
            throw new InvalidMessageError(message.id(),
                    String.format("Invalid Message mode. [id=%s][mode=%s]", message.id(), message.mode().name()));
        }
        txId = checkMessageSequence(message);

        if (message.mode() == MessageObject.MessageMode.Backlog) {
            processBacklogMessage(message, txId);
            txId = -1;
        } else {
            processTxMessage(message, txId);
        }
        return txId;
    }

    private void processTxMessage(MessageObject<String, DFSChangeDelta> message, long txId) throws Exception {
        Object data = ChangeDeltaSerDe.parse(message.value());
        if (data instanceof DFSAddFile) {
            processAddFileTxMessage((DFSAddFile) data, message, txId);
        } else if (data instanceof DFSAppendFile) {
            processAppendFileTxMessage((DFSAppendFile) data, message, txId);
        } else if (data instanceof DFSDeleteFile) {
            processDeleteFileTxMessage((DFSDeleteFile) data, message, txId);
        } else if (data instanceof DFSAddBlock) {
            processAddBlockTxMessage((DFSAddBlock) data, message, txId);
        } else if (data instanceof DFSUpdateBlocks) {
            processUpdateBlocksTxMessage((DFSUpdateBlocks) data, message, txId);
        } else if (data instanceof DFSTruncateBlock) {
            processTruncateBlockTxMessage((DFSTruncateBlock) data, message, txId);
        } else if (data instanceof DFSCloseFile) {
            processCloseFileTxMessage((DFSCloseFile) data, message, txId);
        } else if (data instanceof DFSRenameFile) {
            processRenameFileTxMessage((DFSRenameFile) data, message, txId);
        } else if (data instanceof DFSIgnoreTx) {
            processIgnoreTxMessage((DFSIgnoreTx) data, message, txId);
        } else {
            throw new InvalidMessageError(message.id(),
                    String.format("Message Body type not supported. [type=%s]", data.getClass().getCanonicalName()));
        }
    }

    private void processAddFileTxMessage(DFSAddFile data,
                                         MessageObject<String, DFSChangeDelta> message,
                                         long txId) throws Exception {
        DFSFileState fileState = stateManager.create(data.getFile().getPath(),
                data.getFile().getInodeId(),
                data.getModifiedTime(),
                data.getBlockSize(),
                data.getTransaction().getTransactionId());
        /*
        List<DFSBlock> blocks = data.getBlocksList();
        if (!blocks.isEmpty()) {
            for (DFSBlock block : blocks) {
                fileState = stateManager.addOrUpdateBlock(fileState.getHdfsFilePath(),
                        block.getBlockId(),
                        data.getModifiedTime(),
                        block.getSize(),
                        block.getGenerationStamp(),
                        data.getTransaction().getTransactionId());
            }
        }
         */
        String domain = stateManager.domainManager().matches(fileState.getHdfsFilePath());
        if (!Strings.isNullOrEmpty(domain)) {
            DFSReplicationState rState = stateManager.create(fileState.getId(), fileState.getHdfsFilePath(), true);
            rState.setSnapshotTxId(fileState.getLastTnxId());
            rState.setSnapshotTime(System.currentTimeMillis());
            rState.setSnapshotReady(true);

            stateManager.update(rState);
            sender.send(message);
        }
    }

    private void processAppendFileTxMessage(DFSAppendFile data,
                                            MessageObject<String, DFSChangeDelta> message,
                                            long txId) throws Exception {
        DFSFileState fileState = stateManager.get(data.getFile().getPath());
        if (fileState == null || fileState.isDeleted()) {
            throw new Exception(String.format("NameNode Replica out of sync, missing file state. [path=%s]", data.getFile().getPath()));
        }
        DFSReplicationState rState = stateManager.get(fileState.getId());
        if (rState != null && rState.isEnabled()) {
            DFSFile df = data.getFile();
            df = df.toBuilder().setInodeId(fileState.getId()).build();
            data = data.toBuilder().setFile(df).build();

            message = ChangeDeltaSerDe.create(message.value().getNamespace(), data, DFSAppendFile.class, message.mode());
            sender.send(message);
        }
    }

    private void processDeleteFileTxMessage(DFSDeleteFile data,
                                            MessageObject<String, DFSChangeDelta> message,
                                            long txId) throws Exception {
        DFSFileState fileState = stateManager.get(data.getFile().getPath());
        if (fileState == null || fileState.isDeleted()) {
            throw new Exception(String.format("NameNode Replica out of sync, missing file state. [path=%s]", data.getFile().getPath()));
        }
        fileState = stateManager.markDeleted(fileState.getHdfsFilePath());
        DFSReplicationState rState = stateManager.get(fileState.getId());
        if (rState != null) {
            if (rState.isEnabled()) {
                DFSFile df = data.getFile();
                df = df.toBuilder().setInodeId(fileState.getId()).build();
                data = data.toBuilder().setFile(df).build();

                message = ChangeDeltaSerDe.create(message.value().getNamespace(), data, DFSAppendFile.class, message.mode());
                sender.send(message);
            }
        }
        stateManager.delete(fileState.getId());
    }

    private void processAddBlockTxMessage(DFSAddBlock data,
                                          MessageObject<String, DFSChangeDelta> message,
                                          long txId) throws Exception {
        DFSFileState fileState = stateManager.get(data.getFile().getPath());
        if (fileState == null || fileState.isDeleted()) {
            throw new Exception(String.format("NameNode Replica out of sync, missing file state. [path=%s]", data.getFile().getPath()));
        }
        long lastBlockId = -1;
        if (data.hasPenultimateBlock()) {
            lastBlockId = data.getPenultimateBlock().getBlockId();
        }
        fileState = stateManager.addOrUpdateBlock(fileState.getHdfsFilePath(),
                data.getLastBlock().getBlockId(),
                lastBlockId,
                data.getTransaction().getTimestamp(),
                data.getLastBlock().getSize(),
                data.getLastBlock().getGenerationStamp(),
                data.getTransaction().getTransactionId());

        DFSReplicationState rState = stateManager.get(fileState.getId());
        if (rState != null) {
            if (rState.isEnabled()) {
                DFSFile df = data.getFile();
                df = df.toBuilder().setInodeId(fileState.getId()).build();
                data = data.toBuilder().setFile(df).build();

                message = ChangeDeltaSerDe.create(message.value().getNamespace(), data, DFSAppendFile.class, message.mode());
                sender.send(message);
            }
        }
    }

    private void processUpdateBlocksTxMessage(DFSUpdateBlocks data,
                                              MessageObject<String, DFSChangeDelta> message,
                                              long txId) throws Exception {

    }

    private void processTruncateBlockTxMessage(DFSTruncateBlock data,
                                               MessageObject<String, DFSChangeDelta> message,
                                               long txId) throws Exception {

    }

    private void processCloseFileTxMessage(DFSCloseFile data,
                                           MessageObject<String, DFSChangeDelta> message,
                                           long txId) throws Exception {

    }

    private void processRenameFileTxMessage(DFSRenameFile data,
                                            MessageObject<String, DFSChangeDelta> message,
                                            long txId) throws Exception {

    }

    private void processIgnoreTxMessage(DFSIgnoreTx data,
                                        MessageObject<String, DFSChangeDelta> message,
                                        long txId) throws Exception {

    }

    private void processBacklogMessage(MessageObject<String, DFSChangeDelta> message, long txId) throws Exception {
        DFSAddFile addFile = (DFSAddFile) ChangeDeltaSerDe.parse(message.value());
        DFSFileState fileState = stateManager.get(addFile.getFile().getPath());
        if (fileState == null) {
            throw new InvalidMessageError(message.id(),
                    String.format("HDFS File Not found. [path=%s]", addFile.getFile().getPath()));
        }
        DFSReplicationState rState = stateManager.get(fileState.getId());
        if (rState == null || !rState.isEnabled()) {
            throw new InvalidMessageError(message.id(),
                    String.format("HDFS File not registered for snapshot. [path=%s][inode=%d]",
                            addFile.getFile().getPath(), fileState.getId()));
        }
        if (rState.isSnapshotReady()) {
            throw new InvalidMessageError(message.id(),
                    String.format("Snapshot already completed for file. [path=%s][inode=%d]",
                            addFile.getFile().getPath(), fileState.getId()));
        }
        if (rState.getSnapshotTxId() != txId) {
            throw new InvalidMessageError(message.id(),
                    String.format("Snapshot transaction mismatch. [path=%s][inode=%d] [expected=%d][actual=%d]",
                            addFile.getFile().getPath(), fileState.getId(), rState.getSnapshotTxId(), txId));
        }

    }

    private long checkMessageSequence(MessageObject<String, DFSChangeDelta> message) throws Exception {
        long txId = Long.parseLong(message.value().getTxId());
        if (message.mode() == MessageObject.MessageMode.New) {
            NameNodeTxState txState = stateManager.agentTxState();
            if (txState.getProcessedTxId() + 1 != txId) {
                throw new Exception(String.format("Detected missing transaction. [expected TX ID=%d][actual TX ID=%d]",
                        (txState.getProcessedTxId() + 1), txId));
            }
        }
        return txId;
    }

    private boolean isValidMessage(MessageObject<String, DFSChangeDelta> message) {
        boolean ret = false;
        if (message.mode() != null) {
            ret = (message.mode() == MessageObject.MessageMode.New || message.mode() == MessageObject.MessageMode.Backlog);
        }
        if (ret) {
            ret = message.value().hasTxId();
        }
        return ret;
    }

    @Getter
    @Setter
    @Accessors(fluent = true)
    public static class HDFSDeltaChangeProcessorConfig extends ConfigReader {
        public static class Constants {
            public static final String __CONFIG_PATH = "delta.manager";
            public static final String __CONFIG_PATH_SENDER = "sender";
            public static final String __CONFIG_PATH_RECEIVER = "receiver";
            public static final String __CONFIG_PATH_ERROR = "errorQueue";
            public static final String CONFIG_RECEIVE_TIMEOUT = "timeout";
        }

        private MessagingConfig senderConfig;
        private MessagingConfig receiverConfig;
        private MessagingConfig errorConfig;
        private String batchTimeout;

        public HDFSDeltaChangeProcessorConfig(@NonNull HierarchicalConfiguration<ImmutableNode> config) {
            super(config, Constants.__CONFIG_PATH);
        }

        public void read() throws ConfigurationException {
            if (get() == null) {
                throw new ConfigurationException("Kafka Configuration not drt or is NULL");
            }
            try {
                HierarchicalConfiguration<ImmutableNode> config = get().configurationAt(Constants.__CONFIG_PATH_SENDER);
                if (config == null) {
                    throw new ConfigurationException(String.format("Sender configuration node not found. [path=%s]", Constants.__CONFIG_PATH_SENDER));
                }
                senderConfig = new MessagingConfig();
                senderConfig.read(config);
                if (config.containsKey(Constants.CONFIG_RECEIVE_TIMEOUT)) {
                    batchTimeout = config.getString(Constants.CONFIG_RECEIVE_TIMEOUT);
                }

                config = get().configurationAt(Constants.__CONFIG_PATH_RECEIVER);
                if (config == null) {
                    throw new ConfigurationException(String.format("Receiver configuration node not found. [path=%s]", Constants.__CONFIG_PATH_RECEIVER));
                }
                receiverConfig = new MessagingConfig();
                receiverConfig.read(config);

                config = get().configurationAt(Constants.__CONFIG_PATH_ERROR);
                if (config == null) {
                    throw new ConfigurationException(String.format("Error Queue configuration node not found. [path=%s]", Constants.__CONFIG_PATH_ERROR));
                }
                errorConfig = new MessagingConfig();
                errorConfig.read(config);

            } catch (Exception ex) {
                throw new ConfigurationException(ex);
            }
        }
    }
}
