package com.jflop.server.runtime;

import com.jflop.server.admin.data.AgentJVM;
import com.jflop.server.persistency.DocType;
import com.jflop.server.persistency.IndexTemplate;
import com.jflop.server.persistency.PersistentData;
import com.jflop.server.runtime.data.*;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * TODO: Document!
 *
 * @author artem
 *         Date: 10/12/16
 */
@Component
public class RawDataIndex extends IndexTemplate {

    private static final Logger logger = Logger.getLogger(RawDataIndex.class.getName());

    private static final String RAW_DATA_INDEX = "jf-raw-data";

    @Autowired
    private MetadataIndex metadataIndex;

    public RawDataIndex() {
        super(RAW_DATA_INDEX + "-template", RAW_DATA_INDEX + "*",
                new DocType("load", "persistency/loadData.json", LoadData.class),
                new DocType("thread", "persistency/threadOccurrenceData.json", ThreadOccurrenceData.class),
                new DocType("flow", "persistency/flowOccurrenceData.json", FlowOccurrenceData.class),
                new DocType("snapshot", "persistency/snapshotData.json", SnapshotData.class),
                new DocType("liveThreads", "persistency/liveThreadsData.json", LiveThreadsData.class)
        );
    }

    @Override
    public String indexName() {
        // TODO: implement time suffix/alias
        return RAW_DATA_INDEX;
    }

    public void addRawData(Collection<? extends AgentData> dataList) {
        // TODO: use bulk update instead of inserting one by one
        for (AgentData rawData : dataList) {
            createDocument(new PersistentData<>(rawData));
        }
    }

    public <M extends Metadata, O extends OccurrenceData> Map<M, List<O>> getOccurrencesAndMetadata(AgentJVM agentJvm, Class<O> occurrenceType, Class<M> metadataType, Date from, Date to) {
        QueryBuilder query = QueryBuilders.boolQuery()
                .must(agentJvmQuery(agentJvm))
                .must(QueryBuilders.rangeQuery("time").from(from.getTime()).to(to.getTime()));

        List<PersistentData<O>> found = find(query, 10000, occurrenceType, null);

        Map<String, List<O>> id2occurrences = new HashMap<>();
        for (PersistentData<O> persistentData : found) {
            List<O> occurrences = id2occurrences.computeIfAbsent(persistentData.source.getMetadataId(), key -> new ArrayList<>());
            occurrences.add(persistentData.source);
        }
        if (id2occurrences.isEmpty()) return null;

        List<M> metadata = metadataIndex.getDocuments(metadataType, id2occurrences.keySet());
        logger.fine("Retrieved " + metadata.size() + " metadata objects of type " + metadataType.getSimpleName() + " for " + found.size() + " occurrences of type " + occurrenceType.getSimpleName());
        if (metadata.size() != id2occurrences.size()) {
            throw new RuntimeException("Metadata not found for some occurrence IDs");
        }

        Map<M, List<O>> res = new HashMap<>();
        for (M key : metadata) {
            res.put(key, id2occurrences.get(key.getDocumentId()));
        }
        return res;
    }

    private QueryBuilder agentJvmQuery(AgentJVM agentJVM) {
        return QueryBuilders.boolQuery()
                .must(QueryBuilders.termQuery("agentJvm.accountId", agentJVM.accountId))
                .must(QueryBuilders.termQuery("agentJvm.agentId", agentJVM.agentId))
                .must(QueryBuilders.termQuery("agentJvm.jvmId", agentJVM.jvmId));
    }

    public List<LoadData> getLoadData(Date from, Date to) {
        RangeQueryBuilder time = QueryBuilders.rangeQuery("time").from(from.getTime()).to(to.getTime());
        List<PersistentData<LoadData>> found = find(time, 300, LoadData.class, null);
        return found.stream().map(doc -> doc.source).collect(Collectors.toList());
    }
}
