package com.jflop.server.stream;

import com.jflop.server.stream.base.StreamsApplication;
import com.jflop.server.stream.ext.ActiveAgentProcessor;
import com.jflop.server.stream.feature.classinfo.ClassInfoProcessor;
import com.jflop.server.stream.feature.load.LoadDataProcessor;
import com.jflop.server.stream.feature.threads.ThreadDumpProcessor;
import com.jflop.server.stream.ext.AgentFeatureProcessor;

import java.io.IOException;
import java.util.Properties;

/**
 * TODO: Document!
 *
 * @author artem
 *         Date: 03/06/2017
 */
public class AppRunner {

    public static final String IN_TOPIC = "process-in-topic";
    public static final String COMMAND_OUT_TOPIC = "command-topic";

    public static void main(String[] args) throws IOException {
        Properties topologyProperties = new Properties();
        topologyProperties.load(AppRunner.class.getClassLoader().getResourceAsStream("export.properties"));

        StreamsApplication app = new StreamsApplication("processingApp", topologyProperties, StreamsApplication.AutoOffsetReset.latest)
                .addSource(AgentFeatureProcessor.INPUT_SOURCE_ID, IN_TOPIC)
                .addSink(AgentFeatureProcessor.COMMANDS_SINK_ID, COMMAND_OUT_TOPIC)
                .addProcessors(ActiveAgentProcessor.class, LoadDataProcessor.class, ThreadDumpProcessor.class, ClassInfoProcessor.class);

        app.build().start();
    }
}
