package denominator.ultradns;
import static com.google.common.base.Functions.toStringFunction;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Ordering.usingToString;
import static denominator.ultradns.GroupByRecordNameAndTypeIterator.parseRdataList;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.jclouds.ultradns.ws.UltraDNSWSApi;
import org.jclouds.ultradns.ws.domain.ResourceRecord;
import org.jclouds.ultradns.ws.domain.ResourceRecordMetadata;
import org.jclouds.ultradns.ws.features.ResourceRecordApi;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.primitives.UnsignedInteger;

import denominator.ResourceRecordSetApi;
import denominator.ResourceTypeToValue;
import denominator.model.ResourceRecordSet;
import denominator.model.ResourceRecordSet.Builder;

public final class UltraDNSResourceRecordSetApi implements denominator.ResourceRecordSetApi {
    static final class Factory implements denominator.ResourceRecordSetApi.Factory {

        private final UltraDNSWSApi api;

        @Inject
        Factory(UltraDNSWSApi api) {
            this.api = api;
        }

        @Override
        public ResourceRecordSetApi create(final String zoneName) {
            return new UltraDNSResourceRecordSetApi(api.getResourceRecordApiForZone(zoneName));
        }
    }

    private final ResourceRecordApi api;

    UltraDNSResourceRecordSetApi(ResourceRecordApi api) {
        this.api = api;
    }

    @Override
    public Iterator<ResourceRecordSet<?>> list() {
        Iterator<ResourceRecordMetadata> orderedRecords = api.list().toSortedList(usingToString()).iterator();
        return new GroupByRecordNameAndTypeIterator(orderedRecords);
    }

    @Override
    public Optional<ResourceRecordSet<?>> getByNameAndType(String name, String type) {
        List<ResourceRecordMetadata> references = referencesByNameAndType(name, type);
        if (references.isEmpty())
            return Optional.absent();
        
        Optional<UnsignedInteger> ttl = Optional.absent();
        Builder<Map<String, Object>> builder = ResourceRecordSet.builder()
                                                                .name(name)
                                                                .type(type);

        for (ResourceRecordMetadata reference : references) {
            if (!ttl.isPresent())
                ttl = Optional.of(reference.getRecord().getTTL());
            ResourceRecord record = reference.getRecord();
            builder.add(parseRdataList(record.getType(), record.getRData()));
        }
        return Optional.<ResourceRecordSet<?>> of(builder.ttl(ttl.get()).build());
    }

    private List<ResourceRecordMetadata> referencesByNameAndType(final String name, String type) {
        checkNotNull(name, "name");
        checkNotNull(type, "type");
        final UnsignedInteger typeValue = new ResourceTypeToValue().get(type);
        // TODO: temporary until listByNameAndType() works with NS records where
        // name = zoneName
        return api.list().filter(new Predicate<ResourceRecordMetadata>() {
            public boolean apply(ResourceRecordMetadata in) {
                return name.equals(in.getRecord().getName()) && typeValue.equals(in.getRecord().getType());
            }
        }).toSortedList(usingToString());
    }

    private static final UnsignedInteger defaultTTL = UnsignedInteger.fromIntBits(300);

    @Override
    public void add(ResourceRecordSet<?> rrset) {
        checkNotNull(rrset, "rrset was null");
        checkArgument(!rrset.isEmpty(), "rrset was empty %s", rrset);

        Optional<UnsignedInteger> ttlToApply = rrset.getTTL();

        List<ResourceRecordMetadata> references = referencesByNameAndType(rrset.getName(), rrset.getType());

        List<Map<String, Object>> recordsLeftToCreate = Lists.newArrayList(rrset);

        for (ResourceRecordMetadata reference : references) {
            ResourceRecord record = reference.getRecord();
            if (!ttlToApply.isPresent())
                ttlToApply = Optional.of(record.getTTL());
            ResourceRecord updateTTL = record.toBuilder().ttl(ttlToApply.or(defaultTTL)).build();

            Map<String, Object> rdata = parseRdataList(record.getType(), record.getRData());
            if (recordsLeftToCreate.contains(rdata)) {
                recordsLeftToCreate.remove(rdata);
                // all ok.
                if (ttlToApply.get().equals(record.getTTL())) {
                    continue;
                }
                // update ttl of rdata in input
                api.update(reference.getGuid(), updateTTL);
            } else if (!ttlToApply.get().equals(record.getTTL())) {
                // update ttl of other record
                api.update(reference.getGuid(), updateTTL);
            }
        }

        if (recordsLeftToCreate.size() > 0) {
            ResourceRecord.Builder builder = ResourceRecord.rrBuilder()
                                                           .name(rrset.getName())
                                                           .type(new ResourceTypeToValue().get(rrset.getType()))
                                                           .ttl(ttlToApply.or(defaultTTL));
            for (Map<String, Object> rdata : recordsLeftToCreate) {
                api.create(builder.rdata(transform(rdata.values(), toStringFunction())).build());
            }
        }
    }

    @Override
    public void applyTTLToNameAndType(UnsignedInteger ttl, String name, String type) {
        checkNotNull(ttl, "ttl");

        List<ResourceRecordMetadata> references = referencesByNameAndType(name, type);
        if (references.isEmpty())
            return;

        for (ResourceRecordMetadata reference : references) {
            ResourceRecord updateTTL = reference.getRecord().toBuilder().ttl(ttl).build();
            api.update(reference.getGuid(), updateTTL);
        }
    }

    @Override
    public void replace(ResourceRecordSet<?> rrset) {
        checkNotNull(rrset, "rrset was null");
        checkArgument(!rrset.isEmpty(), "rrset was empty %s", rrset);
        UnsignedInteger ttlToApply = rrset.getTTL().or(defaultTTL);

        List<ResourceRecordMetadata> references = referencesByNameAndType(rrset.getName(), rrset.getType());

        List<Map<String, Object>> recordsLeftToCreate = Lists.newArrayList(rrset);

        for (ResourceRecordMetadata reference : references) {
            ResourceRecord record = reference.getRecord();
            Map<String, Object> rdata = parseRdataList(record.getType(), record.getRData());
            if (recordsLeftToCreate.contains(rdata)) {
                recordsLeftToCreate.remove(rdata);
                // all ok.
                if (ttlToApply.equals(record.getTTL())) {
                    continue;
                }
                // update ttl of rdata in input
                api.update(reference.getGuid(), record.toBuilder().ttl(ttlToApply).build());
            } else {
                api.delete(reference.getGuid());
            }
        }

        if (recordsLeftToCreate.size() > 0) {
            ResourceRecord.Builder builder = ResourceRecord.rrBuilder()
                                                           .name(rrset.getName())
                                                           .type(new ResourceTypeToValue().get(rrset.getType()))
                                                           .ttl(ttlToApply);
            for (Map<String, Object> rdata : recordsLeftToCreate) {
                api.create(builder.rdata(transform(rdata.values(), toStringFunction())).build());
            }
        }
    }

    @Override
    public void remove(ResourceRecordSet<?> rrset) {
        checkNotNull(rrset, "rrset was null");
        checkArgument(!rrset.isEmpty(), "rrset was empty %s", rrset);

        List<ResourceRecordMetadata> references = referencesByNameAndType(rrset.getName(), rrset.getType());
        if (references.isEmpty())
            return;
        for (ResourceRecordMetadata reference : references) {
            ResourceRecord record = reference.getRecord();
            if (rrset.contains(parseRdataList(record.getType(), record.getRData())))
                api.delete(reference.getGuid());
        }
    }
}
