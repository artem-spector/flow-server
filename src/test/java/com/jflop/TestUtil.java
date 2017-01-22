package com.jflop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jflop.server.persistency.ESClient;
import com.jflop.server.persistency.PersistentData;
import com.jflop.server.runtime.data.FlowMetadata;
import com.jflop.server.runtime.data.FlowOccurrenceData;
import com.jflop.server.runtime.data.ThreadMetadata;
import com.jflop.server.runtime.data.ThreadOccurrenceData;
import com.jflop.server.runtime.data.processed.FlowSummary;
import com.jflop.server.runtime.data.processed.MethodCall;
import com.jflop.server.runtime.data.processed.MethodFlow;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;

import java.io.*;
import java.util.*;

/**
 * TODO: Document!
 *
 * @author artem on 13/12/2016.
 */
public class TestUtil {

    private static ObjectMapper mapper = new ObjectMapper();

    private ESClient esClient;

    public static void main(String[] args) throws Exception {
        TestUtil util = new TestUtil();
//        util.copyFlowsToFiles();
//        util.copyFlowSummaryToFile();
        util.copyLastThreadsAndFlows();
    }

    public TestUtil() throws Exception {
        esClient = new ESClient("localhost", 9300);
    }

    private void copyFlowsToFiles() throws IOException {
        String folderName = "src/test/resources/samples/sameFlows/1";
        String file1 = "flow1.json";
        String file2 = "flow2.json";

        String id1 = "yzZwcvbZmkpXJPiuDX/+Wh0pOGw=";
        String id2 = "yycuC3q11mOQpiM81z4K+FAvydA=";

        FlowMetadata flow1 = retrieve("jf-metadata", "flow", id1, FlowMetadata.class);
        FlowMetadata flow2 = retrieve("jf-metadata", "flow", id2, FlowMetadata.class);
        saveAsJson(flow1, folderName, file1);
        saveAsJson(flow2, folderName, file2);

        boolean res1 = FlowMetadata.maybeSame(flow1, flow2);
        System.out.println("1 same as 2: " + res1);
    }

    private void copyFlowSummaryToFile() throws IOException {
        String folderName = "src/test/resources/samples/flowSummary/5";

        String summaryId = "AVnBXIC37vTJEsJloqqt";
        String summaryFile = "summary1.json";

        String flowStr = "{\"name\":\"m8\",\"duration\":94,\"nested\":[{\"name\":\"m7\",\"duration\":4,\"nested\":[{\"name\":\"m1\",\"duration\":1,\"nested\":[{\"name\":\"m2\",\"duration\":0,\"nested\":[{\"name\":\"m3\",\"duration\":0}]},{\"name\":\"m6\",\"duration\":1}]},{\"name\":\"m4\",\"duration\":2,\"nested\":[{\"name\":\"m5\",\"duration\":0,\"nested\":[]}]}]}]}";
        String flowFile = "generatedFlow1.json";


        FlowSummary flowSummary = retrieve("jf-processed-data", "flowSummary", summaryId, FlowSummary.class);
        saveAsJson(flowSummary, folderName, summaryFile);
        int flowCount = 1;
        for (MethodCall root : flowSummary.roots) {
            for (MethodFlow methodFlow : root.flows) {
                FlowMetadata flowMetadata = retrieve("jf-metadata", "flow", methodFlow.flowId, FlowMetadata.class);
                saveAsJson(flowMetadata, folderName, "flow" + (flowCount++) + ".json");
            }
        }

        saveString(flowStr, folderName, flowFile);
    }

    private void copyLastThreadsAndFlows() throws IOException {
        String folderName = "src/test/resources/samples/threadsAndFlows/2";

        FlowSummary summary = findLast("jf-processed-data", "flowSummary", FlowSummary.class);
        Date to = summary.time;
        Date from = new Date(to.getTime() - 3000);

        List<ThreadOccurrenceData> threadOccurrences = findAll("jf-raw-data", "thread", from, to, 100, ThreadOccurrenceData.class);
        saveListAsJson(threadOccurrences, folderName, "threadOccurrence", ".json");
        Set<String> threadIds = new HashSet<>();
        threadOccurrences.forEach(occ -> threadIds.add(occ.getMetadataId()));
        List<Object> threadValues = new ArrayList<>();
        threadIds.forEach(id -> threadValues.add(retrieve("jf-metadata", "thread", id, ThreadMetadata.class)));
        saveListAsJson(threadValues, folderName, "threadMetadata", ".json");

        List<FlowOccurrenceData> flowOccurrences = findAll("jf-raw-data", "flow", from, to, 100, FlowOccurrenceData.class);
        saveListAsJson(flowOccurrences, folderName, "flowOccurrence", ".json");
        Set<String> flowIds = new HashSet<>();
        flowOccurrences.forEach(occ -> flowIds.add(occ.getMetadataId()));
        List<Object> flowValues = new ArrayList<>();
        flowIds.forEach(id -> flowValues.add(retrieve("jf-metadata", "flow", id, FlowMetadata.class)));
        saveListAsJson(flowValues, folderName, "flowMetadata", ".json");
    }

    private <T> T retrieve(String index, String doctype, String id, Class<T> valueType) {
        PersistentData<T> doc = esClient.getDocument(index, doctype, new PersistentData<>(id, 0), valueType);
        if (doc == null)
            throw new RuntimeException("Document does not exist: " + index + "/" + doctype + "/" + id);
        return doc.source;
    }

    private <T> T findLast(String index, String doctype, Class<T> valueType) {
        SearchResponse response = esClient.search(index, doctype, QueryBuilders.matchAllQuery(), 1, SortBuilders.fieldSort("time").order(SortOrder.DESC));
        try {
            return mapper.readValue(response.getHits().getAt(0).source(), valueType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> List<T> findAll(String index, String doctype, Date from, Date to, int maxHits, Class<T> valueType) {
        RangeQueryBuilder timeQuery = QueryBuilders.rangeQuery("time").from(from).to(to);
        SearchResponse response = esClient.search(index, doctype, timeQuery, maxHits, null);
        try {
            List<T> res = new ArrayList<T>();
            for (SearchHit hit : response.getHits().getHits()) {
                res.add(mapper.readValue(hit.source(), valueType));
            }
            return res;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void saveListAsJson(List<? extends Object> values, String folderName, String prefix, String suffix) throws IOException {
        int count = 1;
        for (Object value : values) {
            saveAsJson(value, folderName, prefix + (count++) + suffix);
        }
    }

    private void saveAsJson(Object value, String folderName, String fileName) throws IOException {
        File file = getFile(folderName, fileName);
        FileOutputStream out = new FileOutputStream(file);
        mapper.writeValue(out, value);
        out.flush();
        out.close();
        System.out.println("file saved: " + file.getAbsolutePath());
    }

    private void saveString(String value, String folderName, String fileName) throws IOException {
        File file = getFile(folderName, fileName);
        Writer out = new OutputStreamWriter(new FileOutputStream(file));
        out.write(value + "\n");
        out.flush();
        out.close();
        System.out.println("file saved: " + file.getAbsolutePath());
    }

    private File getFile(String folderName, String fileName) throws IOException {
        File folder = new File(folderName);
        if (!folder.exists() && !folder.mkdirs())
            throw new IOException("Failed to locate or create folder " + folder.getAbsolutePath());
        if (!folder.isDirectory())
            throw new IOException("Not a folder: " + folder.getAbsolutePath());

        return new File(folder, fileName);
    }

    public static <T> T readValueFromFile(String fileName, Class<T> valueType) throws IOException {
        File in = new File(fileName);
        return mapper.readValue(in, valueType);
    }

    public static String readStringFromFile(String fileName) throws IOException {
        BufferedReader in = new BufferedReader(new FileReader(fileName));
        String res = "";
        String line;
        while ((line = in.readLine()) != null) res += line + "\n";
        return res;
    }
}
