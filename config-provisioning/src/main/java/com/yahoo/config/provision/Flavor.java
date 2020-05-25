// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.provision;

import com.yahoo.config.provision.host.FlavorOverrides;
import com.yahoo.config.provisioning.FlavorsConfig;

import java.util.Objects;
import java.util.Optional;

/**
 * A host or node flavor.
 * *Host* flavors come from a configured set which corresponds to the actual flavors available in a zone.
 * *Node* flavors are simply a wrapper of a NodeResources object.
 *
 * @author bratseth
 */
public class Flavor {

    private final boolean configured;
    private final String name;
    private final int cost;
    private final Type type;

    /** The hardware resources of this flavor */
    private final NodeResources resources;

    private final double minCpuCores;

    private final Optional<FlavorOverrides> flavorOverrides;

    /** Creates a *host* flavor from configuration */
    public Flavor(FlavorsConfig.Flavor flavorConfig) {
        this(flavorConfig.name(),
             new NodeResources(flavorConfig.minCpuCores() * flavorConfig.cpuSpeedup(),
                               flavorConfig.minMainMemoryAvailableGb(),
                               flavorConfig.minDiskAvailableGb(),
                               flavorConfig.bandwidth() / 1000,
                               flavorConfig.fastDisk() ? NodeResources.DiskSpeed.fast : NodeResources.DiskSpeed.slow,
                               flavorConfig.remoteStorage() ? NodeResources.StorageType.remote : NodeResources.StorageType.local),
             Optional.empty(),
             Type.valueOf(flavorConfig.environment()),
             true,
             flavorConfig.cost(),
             flavorConfig.minCpuCores());
    }

    /** Creates a *node* flavor from a node resources spec */
    public Flavor(NodeResources resources) {
        this(resources.toString(), resources, Optional.empty(), Type.DOCKER_CONTAINER, false, 0, resources.vcpu());
    }

    /** Creates a *host* flavor for testing */
    public Flavor(String name, NodeResources resources) {
        this(name, resources, Optional.empty(), Flavor.Type.VIRTUAL_MACHINE, true, 0, resources.vcpu());
    }

    private Flavor(String name,
                   NodeResources resources,
                   Optional<FlavorOverrides> flavorOverrides,
                   Type type,
                   boolean configured,
                   int cost,
                   double minCpuCores) {
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.resources = Objects.requireNonNull(resources, "Resources cannot be null");
        this.flavorOverrides = Objects.requireNonNull(flavorOverrides, "Flavor overrides cannot be null");
        this.type = Objects.requireNonNull(type, "Type cannot be null");
        this.configured = configured;
        this.cost = cost;
        this.minCpuCores = minCpuCores;
    }

    public Flavor with(FlavorOverrides flavorOverrides) {
        if (!configured)
            throw new IllegalArgumentException("Cannot override non-configured flavor");

        NodeResources newResources = resources.withDiskGb(flavorOverrides.diskGb().orElseGet(resources::diskGb));
        return new Flavor(name, newResources, Optional.of(flavorOverrides), type, true, cost, minCpuCores);
    }

    public Flavor with(NodeResources resources) {
        if (type == Type.DOCKER_CONTAINER && !configured)
            return new Flavor(resources);

        if (!resources.equals(this.resources.withDiskGb(resources.diskGb())))
            throw new IllegalArgumentException("Can only override disk GB for configured flavor");

        return with(FlavorOverrides.ofDisk(resources.diskGb()));
    }

    /** Returns the unique identity of this flavor if it is configured, or the resource spec string otherwise */
    public String name() { return name; }

    /**
     * Get the monthly cost (total cost of ownership) in USD for this flavor, typically total cost
     * divided by 36 months.
     * 
     * @return monthly cost in USD
     */
    public int cost() { return cost; }
    
    /**
     * True if this is a configured flavor used for hosts,
     * false if it is a virtual flavor created on the fly from node resources
     */
    public boolean isConfigured() { return configured; }

    public NodeResources resources() { return resources; }

    public Optional<FlavorOverrides> flavorOverrides() { return flavorOverrides; }

    // TODO: Remove after June 2020
    @Deprecated
    public double getMinMainMemoryAvailableGb() { return resources.memoryGb(); }

    // TODO: Remove after June 2020
    @Deprecated
    public double getMinDiskAvailableGb() { return resources.diskGb(); }

    // TODO: Remove after June 2020
    @Deprecated
    public boolean hasFastDisk() { return resources.diskSpeed() == NodeResources.DiskSpeed.fast; }

    // TODO: Remove after June 2020
    @Deprecated
    public double getBandwidthGbps() { return resources.bandwidthGbps(); }

    /** Returns the number of cores available in this flavor, not scaled for speed. */
    // TODO: Remove after June 2020
    @Deprecated
    public double getMinCpuCores() { return minCpuCores; }

    public Type getType() { return type; }
    
    /** Convenience, returns getType() == Type.DOCKER_CONTAINER */
    public boolean isDocker() { return type == Type.DOCKER_CONTAINER; }

    @Override
    public int hashCode() { return Objects.hash(name, flavorOverrides); }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if ( ! (o instanceof Flavor)) return false;
        Flavor other = (Flavor)o;
        if (configured)
            return Objects.equals(this.name, other.name) &&
                    Objects.equals(this.flavorOverrides, other.flavorOverrides);
        else
            return this.resources.equals(other.resources);
    }

    @Override
    public String toString() {
        if (isConfigured())
            return "flavor '" + name + "'" + flavorOverrides.map(o -> " with overrides: " + o).orElse("");
        else
            return name;
    }

    public enum Type {
        undefined, // Default value in config (flavors.def)
        BARE_METAL,
        VIRTUAL_MACHINE,
        DOCKER_CONTAINER
    }

}
