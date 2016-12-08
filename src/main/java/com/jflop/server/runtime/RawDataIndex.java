package com.jflop.server.runtime;

import com.jflop.server.admin.data.AgentJVM;
import com.jflop.server.persistency.DocType;
import com.jflop.server.persistency.IndexTemplate;
import com.jflop.server.persistency.PersistentData;
import com.jflop.server.runtime.data.AgentData;
import com.jflop.server.runtime.data.LoadData;
import com.jflop.server.runtime.data.ThreadOccurrenceData;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * TODO: Document!
 *
 * @author artem
 *         Date: 10/12/16
 */
@Component
public class RawDataIndex extends IndexTemplate {

    private static final String RAW_DATA_INDEX = "jf-raw-data";

    public RawDataIndex() {
        super(RAW_DATA_INDEX + "-template", RAW_DATA_INDEX + "*",
                new DocType("load", "persistency/loadData.json", LoadData.class),
                new DocType("occurrence", "persistency/threadOccurrenceData.json", ThreadOccurrenceData.class)
        );
    }

    @Override
    public String indexName() {
        // TODO: implement time suffix/alias
        return RAW_DATA_INDEX;
    }

    public void addRawData(List<AgentData> dataList) {
        // TODO: use bulk update instead of inserting one by one
        for (AgentData rawData : dataList) {
            createDocument(new PersistentData<>(rawData));
        }
    }

    public <T extends AgentData> List<T> getRawData(AgentJVM agentJVM, Class<T> rawDataClass, Date fromTime, int maxHits) {
        QueryBuilder query = QueryBuilders.boolQuery()
                .must(QueryBuilders.rangeQuery("time").gte(fromTime))
                .must(QueryBuilders.termQuery("agentJvm.accountId", agentJVM.accountId))
                .must(QueryBuilders.termQuery("agentJvm.agentId", agentJVM.agentId))
                .must(QueryBuilders.termQuery("agentJvm.jvmId", agentJVM.jvmId));

        List<PersistentData<T>> found = find(query, maxHits, rawDataClass);

        List<T> res = new ArrayList<T>();
        for (PersistentData<T> doc : found) {
            res.add(doc.source);
        }
        return res;
    }

    public Set<String> getRecentDumps(AgentJVM agentJvm, Date fromTime) {
        return null;
    }
}