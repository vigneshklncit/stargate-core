package com.tuplejump.stargate.cas;

import com.tuplejump.stargate.Fields;
import com.tuplejump.stargate.Utils;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Pair;
import org.apache.commons.collections.iterators.ArrayIterator;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * User: satya
 */
public class ScanIterator extends ColumnFamilyStore.AbstractScanIterator {
    ColumnFamilyStore table;
    org.apache.lucene.search.IndexSearcher searcher;
    ExtendedFilter filter;
    ArrayIterator indexIterator;
    FilterChain chain;
    boolean needsFiltering;
    SortedDocValues rowKeyValues;
    NumericDocValues tsValues;
    SearchSupport searchSupport;

    public ScanIterator(SearchSupport searchSupport, ColumnFamilyStore table, IndexSearcher searcher, ExtendedFilter filter, FilterChain chain, Query query, boolean needsFiltering) throws IOException {
        this.searchSupport = searchSupport;
        this.table = table;
        this.searcher = searcher;
        this.filter = filter;
        this.chain = chain;
        this.needsFiltering = needsFiltering;
        this.rowKeyValues = Fields.getPKDocValues(searcher);
        this.tsValues = Fields.getTSDocValues(searcher);
        int maxResults = filter.maxRows();

        Utils.SimpleTimer timer2 = Utils.getStartedTimer(SearchSupport.logger);
        TopDocs topDocs = searcher.search(query, maxResults);
        timer2.endLogTime("For TopDocs search for -" + topDocs.totalHits + " results");
        if (SearchSupport.logger.isDebugEnabled()) {
            SearchSupport.logger.debug(String.format("Search results [%s]", topDocs.totalHits));
        }
        indexIterator = new ArrayIterator(topDocs.scoreDocs);

    }

    @Override
    public boolean needsFiltering() {
        return needsFiltering;
    }

    @Override
    protected Row computeNext() {
        DataRange range = filter.dataRange;
        SliceQueryFilter sliceQueryFilter = (SliceQueryFilter) filter.dataRange.columnFilter(ByteBufferUtil.EMPTY_BYTE_BUFFER);
        while (indexIterator.hasNext()) {
            try {
                ScoreDoc scoreDoc = (ScoreDoc) indexIterator.next();
                ByteBuffer primaryKey = Fields.primaryKey(rowKeyValues, scoreDoc.doc);
                if (chain != null && !chain.accepts(primaryKey)) {
                    continue;
                }

                Pair<DecoratedKey, IDiskAtomFilter> keyAndFilter = getFilterAndKey(primaryKey, sliceQueryFilter);
                if (keyAndFilter == null) {
                    continue;
                }

                DecoratedKey dk = keyAndFilter.left;
                if (!range.contains(dk)) {
                    if (SearchSupport.logger.isTraceEnabled()) {
                        SearchSupport.logger.trace("Skipping entry {} outside of assigned scan range", dk.token);
                    }
                    continue;
                }

                if (SearchSupport.logger.isTraceEnabled()) {
                    SearchSupport.logger.trace("Returning index hit for {}", dk);
                }
                long ts = tsValues.get(scoreDoc.doc);
                Row row = getRow(keyAndFilter.right, dk, ts);
                if (row == null) {
                    if (SearchSupport.logger.isTraceEnabled())
                        SearchSupport.logger.trace("Returned Row is null");
                    continue;
                }
                return row;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return endOfData();
    }

    private Row getRow(IDiskAtomFilter dataFilter, DecoratedKey dk, long ts) throws IOException {
        ColumnFamily data = table.getColumnFamily(new QueryFilter(dk, table.name, dataFilter, filter.timestamp));
        if (data == null || searchSupport.deleteIfNotLatest(ts, dk.key, data)) {
            return null;
        }
        return new Row(dk, data);
    }


    private Pair<DecoratedKey, IDiskAtomFilter> getFilterAndKey(ByteBuffer primaryKey, SliceQueryFilter sliceQueryFilter) {
        DecoratedKey dk;
        IDiskAtomFilter dataFilter;
        if (table.metadata.getCfDef().isComposite) {
            ByteBuffer[] components = Utils.getCompositePKComponents(table, primaryKey);
            ByteBuffer rowKey = Utils.getRowKeyFromPKComponents(components);
            dk = table.partitioner.decorateKey(rowKey);
            final CompositeType baseComparator = (CompositeType) table.getComparator();
            int prefixSize = baseComparator.types.size() - (table.metadata.getCfDef().hasCollections ? 2 : 1);

            CompositeType.Builder builder = baseComparator.builder();

            for (int i = 0; i < prefixSize; i++)
                builder.add(components[i + 1]);

            ByteBuffer start = builder.build();
            if (!sliceQueryFilter.maySelectPrefix(table.getComparator(), start)) return null;

            ArrayList<ColumnSlice> allSlices = new ArrayList<>();
            ColumnSlice dataSlice = new ColumnSlice(start, builder.buildAsEndOfRange());
            if (table.metadata.hasStaticColumns()) {
                ColumnSlice staticSlice = new ColumnSlice(ByteBufferUtil.EMPTY_BYTE_BUFFER, table.metadata.getStaticColumnNameBuilder().buildAsEndOfRange());
                allSlices.add(staticSlice);
            }
            allSlices.add(dataSlice);
            ColumnSlice[] slices = new ColumnSlice[allSlices.size()];
            allSlices.toArray(slices);
            dataFilter = new SliceQueryFilter(slices, false, Integer.MAX_VALUE, table.metadata.clusteringKeyColumns().size());

        } else {
            dk = table.partitioner.decorateKey(primaryKey);
            dataFilter = filter.columnFilter(primaryKey);
        }
        return Pair.create(dk, dataFilter);
    }


    @Override
    public void close() throws IOException {
        //no op
    }

}
