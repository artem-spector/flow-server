package com.jflop.server.take2.admin;

import com.jflop.server.persistency.DocType;
import com.jflop.server.persistency.IndexTemplate;
import com.jflop.server.persistency.PersistentData;
import com.jflop.server.take2.admin.data.AgentJVM;
import com.jflop.server.take2.admin.data.AgentJvmState;
import com.jflop.server.take2.admin.data.FeatureCommand;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * TODO: Document!
 *
 * @author artem
 *         Date: 9/17/16
 */
@Component
public class AgentJVMIndex extends IndexTemplate {

    private static final String AGENT_JVM_INDEX = "jf-agent-jvm";
    private static final int MAX_JVM_NUM = 500;
    public static final int MAX_FEATURES = 10;

    public AgentJVMIndex() {
        super(AGENT_JVM_INDEX + "-template", AGENT_JVM_INDEX + "*", new DocType("agent", "persistency/agentJvmState.json", AgentJvmState.class));
    }

    @Override
    public String indexName() {
        return AGENT_JVM_INDEX;
    }

    public void deleteAccount(String accountId) {
        deleteByQuery(QueryBuilders.termQuery("accountId", accountId));
    }

    public void deleteAgent(String accountId, String agentId) {
        deleteByQuery(
                QueryBuilders.boolQuery()
                        .must(QueryBuilders.termQuery("accountId", accountId))
                        .must(QueryBuilders.termQuery("agentId", agentId)));
    }

    public List<AgentJvmState> getAgentJvms(String accountId) {
        List<PersistentData<AgentJvmState>> list = find(QueryBuilders.termQuery("accountId", accountId), MAX_JVM_NUM * MAX_FEATURES, AgentJvmState.class);
        List<AgentJvmState> res = new ArrayList<>(list.size());
        for (PersistentData<AgentJvmState> data : list) {
            res.add(data.source);
        }
        return res;
    }

    public PersistentData<AgentJvmState> getAgentJvmState(AgentJVM agentJVM, boolean createIfNotExists) {
        PersistentData<AgentJvmState> res = findSingle(
                QueryBuilders.boolQuery()
                        .must(QueryBuilders.termQuery("accountId", agentJVM.accountId))
                        .must(QueryBuilders.termQuery("agentId", agentJVM.agentId))
                        .must(QueryBuilders.termQuery("jvmId", agentJVM.jvmId)),
                AgentJvmState.class);

        if (res != null)
            return res;

        if (createIfNotExists)
            return createDocument(new PersistentData<>(new AgentJvmState(agentJVM)));

        throw new RuntimeException("Invalid JVM ID");
    }

    public boolean setCommand(AgentJVM agentJVM, String featureId, FeatureCommand command) {
        PersistentData<AgentJvmState> document = getAgentJvmState(agentJVM, false);
        document.source.setCommand(command);

        PersistentData<AgentJvmState> res = updateDocument(document);
        return res.version > document.version;
    }

}
