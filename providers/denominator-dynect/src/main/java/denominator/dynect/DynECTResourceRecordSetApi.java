package denominator.dynect;

import static com.google.common.collect.Ordering.usingToString;

import java.util.Iterator;
import java.util.Map;

import javax.inject.Inject;

import org.jclouds.dynect.v3.DynECTApi;
import org.jclouds.dynect.v3.domain.Record;
import org.jclouds.dynect.v3.domain.RecordId;
import org.jclouds.dynect.v3.features.RecordApi;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

import denominator.ResourceRecordSetApi;
import denominator.model.ResourceRecordSet;
import denominator.model.ResourceRecordSet.Builder;

public final class DynECTResourceRecordSetApi implements denominator.ResourceRecordSetApi {
    static final class Factory implements denominator.ResourceRecordSetApi.Factory {

        private final DynECTApi api;

        @Inject
        Factory(DynECTApi api) {
            this.api = api;
        }

        @Override
        public ResourceRecordSetApi create(final String zoneName) {
            return new DynECTResourceRecordSetApi(api.getRecordApiForZone(zoneName));
        }
    }

    private final RecordApi api;

    DynECTResourceRecordSetApi(RecordApi api) {
        this.api = api;
    }

    @Override
    public FluentIterable<ResourceRecordSet<?>> list() {
        return FluentIterable.from(groupByRecordNameAndType(api));
    }

    private static Iterable<ResourceRecordSet<?>> groupByRecordNameAndType(final RecordApi api) {
        return new Iterable<ResourceRecordSet<?>>() {

            @Override
            public Iterator<ResourceRecordSet<?>> iterator() {
                return new GroupByRecordNameAndTypeIterator(api, api.list().toSortedList(usingToString()).iterator());
            }

        };
    }

    private static class GroupByRecordNameAndTypeIterator implements Iterator<ResourceRecordSet<?>> {

        private final PeekingIterator<RecordId> peekingIterator;
        private final RecordApi api;

        public GroupByRecordNameAndTypeIterator(RecordApi api, Iterator<RecordId> sortedIterator) {
            this.api = api;
            this.peekingIterator = Iterators.peekingIterator(sortedIterator);
        }

        @Override
        public boolean hasNext() {
            return peekingIterator.hasNext();
        }

        @Override
        public ResourceRecordSet<?> next() {
            Record<?> record = api.get(peekingIterator.next());
            Builder<Map<String, Object>> builder = ResourceRecordSet.builder().name(record.getFQDN())
                    .type(record.getType()).ttl(record.getTTL().intValue()).add(record.getRData());
            Record<?> next;
            while (hasNext() && fqdnAndTypeEquals(next = api.get(peekingIterator.next()), record)) {
                peekingIterator.next();
                builder.add(next.getRData());
            }
            return builder.build();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    private static boolean fqdnAndTypeEquals(RecordId actual, RecordId expected) {
        return actual.getFQDN().equals(expected.getFQDN()) && actual.getType().equals(expected.getType());
    }
}