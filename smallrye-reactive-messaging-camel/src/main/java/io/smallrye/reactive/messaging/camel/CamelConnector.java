package io.smallrye.reactive.messaging.camel;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.reactive.streams.api.CamelReactiveStreamsService;
import org.apache.camel.component.reactive.streams.engine.DefaultCamelReactiveStreamsServiceFactory;
import org.apache.camel.component.reactive.streams.engine.ReactiveStreamsEngineConfiguration;
import org.apache.camel.spi.Synchronization;
import org.apache.camel.support.DefaultExchange;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.eclipse.microprofile.reactive.messaging.spi.IncomingConnectorFactory;
import org.eclipse.microprofile.reactive.messaging.spi.OutgoingConnectorFactory;
import org.eclipse.microprofile.reactive.streams.operators.PublisherBuilder;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;
import org.eclipse.microprofile.reactive.streams.operators.SubscriberBuilder;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Connector(CamelConnector.CONNECTOR_NAME)
public class CamelConnector implements IncomingConnectorFactory, OutgoingConnectorFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(CamelConnector.class);
    private static final String REACTIVE_STREAMS_SCHEME = "reactive-streams:";
    public static final String CONNECTOR_NAME = "smallrye-camel";

    @Inject
    private CamelContext camel;

    private CamelReactiveStreamsService reactive;

    @Produces
    public CamelReactiveStreamsService getCamelReactive() {
        return reactive;
    }

    @PostConstruct
    @Inject
    public void init(Instance<Config> config) {
        DefaultCamelReactiveStreamsServiceFactory factory = new DefaultCamelReactiveStreamsServiceFactory();
        ReactiveStreamsEngineConfiguration configuration = new ReactiveStreamsEngineConfiguration();
        if (!config.isUnsatisfied()) {
            // TODO Ask ASD about this resolution issue
            Config conf = config.stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Unable to retrieve the config"));

            conf.getOptionalValue("camel.component.reactive-streams.internal-engine-configuration.thread-pool-max-size",
                    Integer.class)
                    .ifPresent(configuration::setThreadPoolMaxSize);

            conf.getOptionalValue("camel.component.reactive-streams.internal-engine-configuration.thread-pool-min-size",
                    Integer.class)
                    .ifPresent(configuration::setThreadPoolMinSize);

            conf.getOptionalValue("camel.component.reactive-streams.internal-engine-configuration.thread-pool-name",
                    String.class)
                    .ifPresent(configuration::setThreadPoolName);
        }
        this.reactive = factory.newInstance(camel, configuration);
    }

    @Override
    public PublisherBuilder<? extends Message<?>> getPublisherBuilder(Config config) {
        String name = config.getOptionalValue("endpoint-uri", String.class)
                .orElseThrow(() -> new IllegalArgumentException("The `endpoint-uri of the endpoint is required"));

        Publisher<Exchange> publisher;
        if (name.startsWith(REACTIVE_STREAMS_SCHEME)) {
            // The endpoint is a reactive streams.
            name = name.substring(REACTIVE_STREAMS_SCHEME.length());
            LOGGER.info("Creating publisher from Camel stream named {}", name);
            publisher = reactive.fromStream(name);
        } else {
            LOGGER.info("Creating publisher from Camel endpoint {}", name);
            publisher = reactive.from(name);
        }

        return ReactiveStreams.fromPublisher(publisher)
                .map(m -> new CamelMessage<>(m));
    }

    @Override
    public SubscriberBuilder<? extends Message<?>, Void> getSubscriberBuilder(Config config) {
        String name = config.getOptionalValue("endpoint-uri", String.class)
                .orElseThrow(() -> new IllegalArgumentException("The `endpoint-uri` of the endpoint is required"));

        SubscriberBuilder<? extends Message<?>, Void> subscriber;
        if (name.startsWith(REACTIVE_STREAMS_SCHEME)) {
            // The endpoint is a reactive streams.
            name = name.substring(REACTIVE_STREAMS_SCHEME.length());
            LOGGER.info("Creating subscriber from Camel stream named {}", name);
            subscriber = ReactiveStreams.<Message<?>> builder()
                    .map(this::createExchangeFromMessage)
                    .to(reactive.streamSubscriber(name));
        } else {
            LOGGER.info("Creating publisher from Camel endpoint {}", name);
            subscriber = ReactiveStreams.<Message<?>> builder()
                    .map(this::createExchangeFromMessage)
                    .to(reactive.subscriber(name));
        }
        return subscriber;
    }

    private Exchange createExchangeFromMessage(Message message) {
        if (message.getPayload() instanceof Exchange) {
            return (Exchange) message.getPayload();
        }
        DefaultExchange exchange = new DefaultExchange(camel);
        exchange.getIn().setBody(message.getPayload());
        exchange.addOnCompletion(new Synchronization() {
            @Override
            public void onComplete(Exchange exchange) {
                message.ack();
            }

            @Override
            public void onFailure(Exchange exchange) {
                LOGGER.error("Exchange failed");
            }
        });
        return exchange;
    }
}
