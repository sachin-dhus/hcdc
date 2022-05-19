package ai.sapper.hcdc.core.connections;

import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.kafka.clients.producer.KafkaProducer;

import javax.naming.ConfigurationException;

@Getter
@Accessors(fluent = true)
public class KafkaProducerConnection<K, V> extends KafkaConnection {
    private KafkaProducer<K, V> producer;

    /**
     * @param xmlConfig
     * @return
     * @throws ConnectionError
     */
    @Override
    public Connection init(@NonNull HierarchicalConfiguration<ImmutableNode> xmlConfig) throws ConnectionError {
        synchronized (state()) {
            super.init(xmlConfig);
            try {
                if (kafkaConfig().mode() != EKafkaClientMode.Producer) {
                    throw new ConfigurationException("Connection not initialized in Producer mode.");
                }
                producer = new KafkaProducer<K, V>(kafkaConfig().producerProperties());

                state.state(EConnectionState.Initialized);
            } catch (Throwable t) {
                state.error(t);
                throw new ConnectionError("Error opening HDFS connection.", t);
            }
        }
        return this;
    }
}
