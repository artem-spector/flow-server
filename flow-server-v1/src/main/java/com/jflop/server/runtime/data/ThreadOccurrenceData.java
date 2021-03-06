package com.jflop.server.runtime.data;

/**
 * Represents the number of occurrences of a specific thread in one thread dump
 *
 * @author artem on 12/6/16.
 */
public class ThreadOccurrenceData extends OccurrenceData {

    public String dumpId;
    public Thread.State threadState;
    public int count;

    @Override
    public String getMetadataId() {
        return dumpId;
    }

}
