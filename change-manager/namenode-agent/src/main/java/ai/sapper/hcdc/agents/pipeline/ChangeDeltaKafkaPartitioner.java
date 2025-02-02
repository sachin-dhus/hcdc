package ai.sapper.hcdc.agents.pipeline;

import ai.sapper.hcdc.common.model.DFSChangeDelta;
import ai.sapper.hcdc.core.messaging.KafkaPartitioner;
import lombok.NonNull;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.parquet.Strings;

import java.util.Objects;

public class ChangeDeltaKafkaPartitioner implements KafkaPartitioner<DFSChangeDelta> {
    private static final String __CONFIG_PATH = "partitioner.config";
    private static final String CONFIG_PARTITION_COUNT = "partitions";

    private HierarchicalConfiguration<ImmutableNode> config;
    private int partitionCount;

    /**
     * @param xmlConfig
     * @throws ConfigurationException
     */
    @Override
    public void init(HierarchicalConfiguration<ImmutableNode> xmlConfig) throws ConfigurationException {
        config = xmlConfig.configurationAt(__CONFIG_PATH);
        String pcount = config.getString(CONFIG_PARTITION_COUNT);
        if (Strings.isNullOrEmpty(pcount)) {
            throw new ConfigurationException(
                    String.format("Partitioner configuration node not found. [path=%s]", CONFIG_PARTITION_COUNT));
        }
        partitionCount = Integer.parseInt(pcount);
    }

    /**
     * @param key
     * @return
     */
    @Override
    public int partition(@NonNull DFSChangeDelta key) {
        String pk = key.getEntity();
        if (Strings.isNullOrEmpty(pk)) {
            pk = key.getNamespace();
        }
        int hash = pk.hashCode();
        if (hash < 0) {
            hash *= -1;
        }
        return (hash % partitionCount);
    }
}
