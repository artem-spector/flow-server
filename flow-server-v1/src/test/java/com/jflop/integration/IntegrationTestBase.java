package com.jflop.integration;

import com.jflop.HttpTestClient;
import com.jflop.server.ServerApp;
import com.jflop.server.admin.AccountIndex;
import com.jflop.server.admin.AdminClient;
import com.jflop.server.admin.AgentJVMIndex;
import com.jflop.server.admin.data.AccountData;
import com.jflop.server.admin.data.AgentJVM;
import com.jflop.server.admin.data.AgentJvmState;
import com.jflop.server.admin.data.FeatureCommand;
import com.jflop.server.background.JvmMonitorAnalysis;
import com.jflop.server.feature.InstrumentationConfigurationFeature;
import com.jflop.server.feature.SnapshotFeature;
import com.jflop.server.persistency.IndexTemplate;
import com.jflop.server.persistency.PersistentData;
import com.sample.MultipleFlowsProducer;
import org.elasticsearch.index.query.QueryBuilders;
import org.jflop.config.JflopConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.boot.test.WebIntegrationTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.Assert.assertNull;

/**
 * Downloads agent and dynamically loads it into the current process.
 *
 * @author artem
 *         Date: 12/17/16
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = ServerApp.class)
@WebIntegrationTest()
public abstract class IntegrationTestBase {

    protected static final Logger logger = Logger.getLogger(IntegrationTestBase.class.getName());

    protected static final String MULTIPLE_FLOWS_PRODUCER_INSTRUMENTATION_PROPERTIES = "multipleFlowsProducer.instrumentation.properties";

    private static final String AGENT_NAME = "testAgent";

    protected static AdminClient adminClient;

    protected static AgentJVM agentJVM;

    @Autowired
    protected JvmMonitorAnalysis analysis;

    @Autowired
    private AccountIndex accountIndex;

    @Autowired
    private AgentJVMIndex agentJVMIndex;

    @Autowired
    private Collection<IndexTemplate> allIndexes;

    private MultipleFlowsProducer producer;
    private boolean stopIt;
    private Thread[] loadThreads;

    @Before
    public void activateAgent() throws Exception {
        if (adminClient != null) return;

        for (IndexTemplate index : allIndexes) index.deleteIndex();

        analysis.stop();

        HttpTestClient client = new HttpTestClient("http://localhost:8080");
        adminClient = new AdminClient(client, "testAccount");
        String agentId = adminClient.createAgent(AGENT_NAME);
        accountIndex.refreshIndex();
        PersistentData<AccountData> account = accountIndex.findSingle(QueryBuilders.termQuery("accountName", "testAccount"), AccountData.class);
        String accountId = account.id;

        byte[] bytes = adminClient.downloadAgent(agentId);
        logger.info("Downloaded agent for agentId: " + agentId);
        File file = new File("target/jflop-agent-test.jar");
        FileOutputStream out = new FileOutputStream(file);
        out.write(bytes);
        out.close();
        loadAgent(file.getPath());

        String jvmId = awaitJvmStateChange(System.currentTimeMillis(), 3).getKey();
        agentJVM = new AgentJVM(accountId, agentId, jvmId);
    }

    @After
    public void clean() {
        stopLoad();
    }

    protected void refreshAll() {
        for (IndexTemplate index : allIndexes) {
            index.refreshIndex();
        }
    }

    protected FeatureCommand awaitFeatureResponse(String featureId, long fromTime, int timeoutSec, CommandValidator waitFor) throws Exception {
        long timeoutMillis = fromTime + timeoutSec * 1000;

        PersistentData<AgentJvmState> previous = null;
        while (System.currentTimeMillis() < timeoutMillis) {
            PersistentData<AgentJvmState> jvmState = agentJVMIndex.getAgentJvmState(agentJVM, false);
            if (previous != null && previous.version == jvmState.version) continue;

            FeatureCommand command = jvmState.source.getCommand(featureId);
            if (command != null && command.respondedAt != null && command.respondedAt.getTime() > fromTime
                    && (waitFor == null || waitFor.validateCommand(command))) {
                return command;
            }
            previous = jvmState;
            Thread.sleep(300);
        }
        throw new Exception("Feature state not changed in " + timeoutSec + " sec");
    }

    protected void startLoad(int numThreads) {
        Random random = new Random();
        producer = new MultipleFlowsProducer();
        stopIt = false;
        loadThreads = new Thread[numThreads];
        for (int i = 0; i < loadThreads.length; i++) {
            loadThreads[i] = new Thread("ProcessingThread_" + i) {
                public void run() {
                    for (int i = 1; !stopIt; i++) {
                        String user = "usr" + i + random.nextInt(100);
                        for (int j = 0; j < 20; j++) {
                            try {
                                producer.serve(user);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            };
            loadThreads[i].start();
        }
        logger.fine("started load " + numThreads);
    }

    protected void stopLoad() {
        stopIt = true;
        if (loadThreads == null) return;

        for (Thread thread : loadThreads)
            thread.interrupt();
        for (Thread thread : loadThreads) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        for (Thread thread : loadThreads) {
            if (thread.isAlive())
                logger.warning("Thread " + thread.getName() + " is alive after 5 sec");
        }

        loadThreads = null;
        logger.fine("load stopped");
    }

    protected String configurationAsText(JflopConfiguration configuration) throws IOException {
        if (configuration == null) return null;
        StringWriter writer = new StringWriter();
        configuration.toProperties().store(writer, null);
        return writer.toString();
    }

    protected JflopConfiguration setConfiguration(JflopConfiguration conf) throws Exception {
        adminClient.submitCommand(agentJVM, InstrumentationConfigurationFeature.FEATURE_ID, InstrumentationConfigurationFeature.SET_CONFIG, configurationAsText(conf));
        FeatureCommand command = awaitFeatureResponse(InstrumentationConfigurationFeature.FEATURE_ID, System.currentTimeMillis(), 10, null);
        assertNull(command.errorText);
        return new JflopConfiguration(new ByteArrayInputStream(command.successText.getBytes()));
    }

    protected JflopConfiguration loadInstrumentationConfiguration(String configurationFile) throws java.io.IOException {
        return new JflopConfiguration(getClass().getClassLoader().getResourceAsStream(configurationFile));
    }

    protected String takeSnapshot(int durationSec) throws Exception {
        Map<String, Object> param = new HashMap<>();
        param.put("durationSec", String.valueOf(durationSec));

        adminClient.submitCommand(agentJVM, SnapshotFeature.FEATURE_ID, SnapshotFeature.TAKE_SNAPSHOT, param);
        FeatureCommand command = awaitFeatureResponse(SnapshotFeature.FEATURE_ID, System.currentTimeMillis(), durationSec + 5,
                latest -> {
                    logger.fine("snapshot progress " + latest.progressPercent + "%");
                    return latest.successText != null;
                });
        return command.successText;
    }

    private void loadAgent(String path) throws Exception {
        String nameOfRunningVM = ManagementFactory.getRuntimeMXBean().getName();
        int p = nameOfRunningVM.indexOf('@');
        String pid = nameOfRunningVM.substring(0, p);

        try {
            com.sun.tools.attach.VirtualMachine vm = com.sun.tools.attach.VirtualMachine.attach(pid);
            vm.loadAgent(path, "");
            vm.detach();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map.Entry<String, Map<String, Object>> awaitJvmStateChange(long fromTime, int timeoutSec) throws Exception {
        long timoutMillis = System.currentTimeMillis() + timeoutSec * 1000;
        while (System.currentTimeMillis() < timoutMillis) {
            Map<String, Object> agentState = getAgentState();
            Map<String, Map<String, Object>> jvms = agentState == null ? null : (Map<String, Map<String, Object>>) agentState.get("jvms");
            if (jvms != null && !jvms.isEmpty()) {
                for (Map.Entry<String, Map<String, Object>> entry : jvms.entrySet()) {
                    Long lastReported = (Long) entry.getValue().get("lastReportedAt");
                    if (lastReported != null && lastReported >= fromTime)
                        return entry;
                }
            }
            Thread.sleep(300);
        }
        throw new Exception("JVM state not changed in " + timeoutSec + " sec");
    }

    private Map<String, Object> getAgentState() throws Exception {
        List<Map<String, Object>> agents = adminClient.getAgentsJson();
        for (Map<String, Object> agent : agents) {
            if (AGENT_NAME.equals(agent.get("agentName"))) {
                return agent;
            }
        }
        return null;
    }

    protected interface CommandValidator {
        boolean validateCommand(FeatureCommand command);
    }

}
