package denominator;

import com.google.common.collect.FluentIterable;

import denominator.model.ResourceRecordSet;

public interface ResourceRecordSetApi {

    static interface Factory {
        ResourceRecordSetApi create(String zoneName);
    }

    /**
     * a listing of all resource record sets inside the zone
     * 
     * @throws IllegalArgumentException
     *             if the {@code zoneName} is not found.
     */
    FluentIterable<ResourceRecordSet<?>> list();
}
