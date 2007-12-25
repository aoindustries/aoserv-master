package com.aoindustries.aoserv.master.cluster;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.sql.SQLUtility;
import com.aoindustries.util.StringUtility;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds the optimal mapping of virtual machines to physical resources to balance customer needs and redundant resources.
 *
 * TODO: Provide separate control over secondary processor type, currently only secondary architecture and number of cores are considered.
 * TODO: Make sure can actually map extents and spindle counts to drives.
 * TODO: If two virtual servers are interchangeable, don't try both combinations - implications?
 *
 * @author  AO Industries, Inc.
 */
public final class ClusterOptimizer {

    private static final long EXTENTS_SIZE = 33554432;

    private enum SkipType {
        PRIMARY_MANUAL_SERVER_MISMATCH,
        PRIMARY_PROCESSOR_MISMATCH,
        PRIMARY_CORES_EXCEEDED,
        PRIMARY_RAM_EXCEEDED,
        PRIMARY_PLUS_SECONDARY_DISK_EXTENTS_EXCEEDED,
        PRIMARY_PLUS_SECONDARY_DISK_WEIGHT_EXCEEDED,
        SECONDARY_MANUAL_SERVER_MISMATCH,
        SECONDARY_PROCESSOR_MISMATCH,
        SECONDARY_CORES_EXCEEDED,
        SECONDARY_RAM_EXCEEDED,
        SECONDARY_DISK_EXTENTS_EXCEEDED,
        SECONDARY_DISK_WEIGHT_EXCEEDED
        ;

        public long counter = 0;
    }

    public static void main(String[] args) {
        Server[] servers = Server.getServers();
        VirtualServer[] virtualServers = VirtualServer.getVirtualServers();
        printTotals(servers, virtualServers);
        mapServers(servers, virtualServers, 0);
        System.out.println("Done!!!  Mapped "+mapped);
    }

    private static void printTotals(Server[] servers, VirtualServer[] virtualServers) {
        long totalProcessorCores = 0;
        long totalRam = 0;
        long totalDisk = 0;
        int totalDiskArrays = 0;
        for(Server server : servers) {
            totalProcessorCores += server.processorCores;
            totalRam += server.ram;
            for(Disk disk : server.disks) {
                totalDisk += disk.extents;
                totalDiskArrays++;
            }
        }
        System.out.println("Servers:");
        System.out.println("    Total Processor Cores: " + totalProcessorCores);
        System.out.println("    Total RAM............: " + totalRam + " MB (" + StringUtility.getApproximateSize(totalRam*1048576)+')');
        System.out.println("    Total Disk Space.....: " + totalDisk + " extents (" + StringUtility.getApproximateSize(totalDisk*EXTENTS_SIZE)+')');
        System.out.println("    Total Disk Arrays....: " + totalDiskArrays);

        long totalVirtualProcessorAllocation = 0;
        long totalMinimumRam = 0;
        long totalVirtualDisk = 0;
        long totalVirtualDiskWeight = 0;
        for(VirtualServer virtualServer : virtualServers) {
            totalVirtualProcessorAllocation += virtualServer.processorCores * virtualServer.processorWeight;
            totalMinimumRam += virtualServer.primaryRam;
            for(VirtualDisk virtualDisk : virtualServer.virtualDisks) {
                totalVirtualDisk += virtualDisk.extents*2;
                totalVirtualDiskWeight += virtualDisk.primaryWeight + virtualDisk.secondaryWeight;
            }
        }
        System.out.println("Virtual Servers:");
        System.out.println("    Total Processor Cores: " + SQLUtility.getMilliDecimal(totalVirtualProcessorAllocation));
        System.out.println("    Total Primary RAM....: " + totalMinimumRam + " MB (" + StringUtility.getApproximateSize(totalMinimumRam*1048576)+')');
        System.out.println("    Total Disk Space.....: " + totalVirtualDisk + " extents (" + StringUtility.getApproximateSize(totalVirtualDisk*EXTENTS_SIZE)+')');
        System.out.println("    Total Disk Arrays....: " + SQLUtility.getMilliDecimal(totalVirtualDiskWeight));
    }

    private static long lastMapDisplayedTime = -1;
    private static long callCounter = 0;
    private static void displayProgress(Server[] servers, VirtualServer[] virtualServers, int currentVirtualServer) {
        callCounter++;
        long currentTime = System.currentTimeMillis();
        long timeSince = currentTime-lastMapDisplayedTime;
        if(lastMapDisplayedTime==-1 || timeSince<0 || timeSince>=30000) {
            if(mapped!=0 || skipped!=0) {
                for(int d=0;d<currentVirtualServer;d++) {
                    if(d>0) System.out.print('/');
                    //if(selectedPrimaries[d]<10) System.out.print('0');
                    System.out.print(virtualServers[d].selectedPrimaryServerIndex);
                    System.out.print('.');
                    //if(selectedSecondaries[d]<10) System.out.print('0');
                    System.out.print(virtualServers[d].selectedSecondaryServerIndex);
                }
                System.out.print(" Mapped "+mapped+", skipped "+skipped);
                if(mapped!=0) System.out.print(", skip/map ratio: "+SQLUtility.getDecimal(skipped*100/mapped));
                if(timeSince>0) System.out.print(", "+(callCounter*1000/timeSince)+" calls/sec");
                System.out.println();
                /*
                for(SkipType skipType : SkipType.values()) {
                    System.out.print(skipType.name());
                    System.out.print(' ');
                    for(int c=skipType.name().length(); c<44; c++) System.out.print(' ');
                    System.out.println(skipType.counter);
                }
                 */
            }
            lastMapDisplayedTime = currentTime;
            callCounter = 0;
        }
    }

    private static void displayMapping(Server[] servers, VirtualServer[] virtualServers) {
        final int serversSize = servers.length;
        final int virtualServersSize = virtualServers.length;

        System.out.println("Mapping found with "+skipped+" skips");
        for(int serverIndex=0;serverIndex<serversSize;serverIndex++) {
            Server server = servers[serverIndex];
            System.out.println(server.hostname);
            System.out.println("    Primary:");
            for(int virtualServerIndex=0;virtualServerIndex<virtualServersSize;virtualServerIndex++) {
                if(virtualServers[virtualServerIndex].selectedPrimaryServerIndex==serverIndex) {
                    VirtualServer virtualServer = virtualServers[virtualServerIndex];
                    System.out.println("        "+virtualServer.hostname+":");
                    System.out.println("            Processor Cores.: "+virtualServer.processorCores);
                    System.out.println("            Processor Weight: "+SQLUtility.getMilliDecimal(virtualServer.processorWeight));
                    System.out.println("            Primary RAM.....: "+virtualServer.primaryRam);
                    for(VirtualDisk virtualDisk : virtualServer.virtualDisks) {
                        System.out.println("            Device: "+virtualDisk.device);
                        System.out.println("                32MB Extents..: "+virtualDisk.extents+" ("+StringUtility.getApproximateSize(virtualDisk.extents*EXTENTS_SIZE)+')');
                        System.out.println("                Primary Type..: "+virtualDisk.primaryDiskType);
                        System.out.println("                Primary Weight: "+SQLUtility.getMilliDecimal(virtualDisk.primaryWeight));
                    }
                }
            }
            System.out.println("        Total:");
            System.out.println("    Secondary:");
            for(int failedPrimaryServerIndex=0;failedPrimaryServerIndex<serversSize;failedPrimaryServerIndex++) {
                boolean isFirst = true;
                for(int virtualServerIndex=0;virtualServerIndex<virtualServersSize;virtualServerIndex++) {
                    if(virtualServers[virtualServerIndex].selectedPrimaryServerIndex==failedPrimaryServerIndex && virtualServers[virtualServerIndex].selectedSecondaryServerIndex==serverIndex) {
                        Server failedPrimaryServer = servers[failedPrimaryServerIndex];
                        VirtualServer secondaryVirtualServer = virtualServers[virtualServerIndex];
                        if(isFirst) {
                            System.out.println("        From "+failedPrimaryServer.hostname+":");
                            isFirst = false;
                        }
                        System.out.println("            "+secondaryVirtualServer.hostname);
                        System.out.println("                Processor Cores.: "+secondaryVirtualServer.processorCores);
                        System.out.println("                Processor Weight: "+SQLUtility.getMilliDecimal(secondaryVirtualServer.processorWeight));
                        System.out.println("                Secondary RAM...: "+secondaryVirtualServer.secondaryRam);
                        for(VirtualDisk virtualDisk : secondaryVirtualServer.virtualDisks) {
                            System.out.println("                Device: "+virtualDisk.device);
                            System.out.println("                    32MB Extents....: "+virtualDisk.extents+" ("+StringUtility.getApproximateSize(virtualDisk.extents*EXTENTS_SIZE)+')');
                            System.out.println("                    Secondary Type..: "+virtualDisk.secondaryDiskType);
                            System.out.println("                    Secondary Weight: "+SQLUtility.getMilliDecimal(virtualDisk.secondaryWeight));
                        }
                    }
                }
                if(!isFirst) {
                    System.out.println("            Total:");
                }
            }
        }
    }

    private static long mapped=0;
    private static long skipped=0;

    /**
     * Try all permutations of mappings from virtual server to physical servers, only continuing to the next allocation
     * checks if the total CPU cores, RAM, number of disk arrays, and disk extents is <= what the physical hardware provides.
     * Also only map the permutations when they match minimumProcessorType, requiredProcessorArchitecture, and minimumProcessorSpeed.
     * These are the quick checks that don't need to worry about the actual mappings to specific primary and secondary disk
     * arrays.
     */
    private static void mapServers(Server[] servers, VirtualServer[] virtualServers, int currentVirtualServer) {
        final int serversSize = servers.length;
        final int virtualServersSize = virtualServers.length;

        displayProgress(servers, virtualServers, currentVirtualServer);

        if(currentVirtualServer==virtualServersSize) {
            mapped++;
            //displayMapping(servers, virtualServers);
            //System.exit(0);
        } else {
            final VirtualServer virtualServer = virtualServers[currentVirtualServer];
            // Try each primary server
            for(int primaryServerIndex=0; primaryServerIndex<serversSize; primaryServerIndex++) {
                final Server primaryServer = servers[primaryServerIndex];
                // First allow manual configuration
                if(virtualServer.primaryServerHostname==null || virtualServer.primaryServerHostname.equals(primaryServer.hostname)) {
                    // Only map the virtual server to the primary server if it matches any processor type, architecture, and speed constraints.
                    if(
                        (
                            virtualServer.minimumProcessorType==null
                            || primaryServer.processorType.compareTo(virtualServer.minimumProcessorType)>=0
                        ) && (
                            virtualServer.requiredProcessorArchitecture==null
                            || primaryServer.processorArchitecture==virtualServer.requiredProcessorArchitecture
                        ) && (
                            virtualServer.minimumProcessorSpeed==-1
                            || primaryServer.processorSpeed>=virtualServer.minimumProcessorSpeed
                        )
                    ) {
                        // Stop processing if primaryServer past capacity on either processor cores or RAM
                        final int oldAllocatedProcessorWeight = primaryServer.allocatedProcessorWeight;
                        final int newAllocatedProcessorWeight = oldAllocatedProcessorWeight + virtualServer.processorCores*virtualServer.processorWeight;
                        if((primaryServer.processorCores*1000) >= newAllocatedProcessorWeight) {
                            // Stop processing if primaryServer past capacity on RAM (primary + maximum of the secondaries)
                            final int oldAllocatedPrimaryRAM = primaryServer.allocatedPrimaryRAM;
                            final int newAllocatedPrimaryRAM = oldAllocatedPrimaryRAM + virtualServer.primaryRam;
                            if(primaryServer.ram>=(newAllocatedPrimaryRAM+primaryServer.maximumAllocatedSecondaryRAM)) {
                                virtualServers[currentVirtualServer].selectedPrimaryServerIndex = primaryServerIndex;
                                primaryServer.allocatedProcessorWeight = newAllocatedProcessorWeight;
                                primaryServer.allocatedPrimaryRAM = newAllocatedPrimaryRAM;

                                // TODO: Dan: Continue changes from here
                                // For each disk type, skip if exceeded on either total extents or arrays (allocation)
                                boolean needsSkip = false;
                                for(int e=0;e<DiskType.diskTypes.length;e++) {
                                    DiskType diskType = DiskType.diskTypes[e];
                                    // Calculate total required
                                    long totalVirtualDisk = 0;
                                    long totalVirtualDiskWeight = 0;
                                    for(int d=0;d<=currentVirtualServer;d++) {
                                        if(virtualServers[d].selectedPrimaryServerIndex==primaryServerIndex) {
                                            VirtualServer mappedVirtualServer = virtualServers[d];
                                            for(VirtualDisk virtualDisk : mappedVirtualServer.virtualDisks) {
                                                if(virtualDisk.primaryDiskType==diskType) {
                                                    totalVirtualDisk += virtualDisk.extents;
                                                    totalVirtualDiskWeight += virtualDisk.primaryWeight;
                                                }
                                            }
                                        }
                                    }
                                    for(int d=0;d<currentVirtualServer;d++) {
                                        if(virtualServers[d].selectedSecondaryServerIndex==primaryServerIndex) {
                                            VirtualServer mappedVirtualServer = virtualServers[d];
                                            for(VirtualDisk virtualDisk : mappedVirtualServer.virtualDisks) {
                                                if(virtualDisk.secondaryDiskType==diskType) {
                                                    totalVirtualDisk += virtualDisk.extents;
                                                    totalVirtualDiskWeight += virtualDisk.secondaryWeight;
                                                }
                                            }
                                        }
                                    }
                                    // Calculate total provided by the primaryServer
                                    long totalDiskExtents = 0;
                                    int totalDiskArrays = 0;
                                    for(Disk disk : primaryServer.disks) {
                                        if(disk.diskType==diskType) {
                                            totalDiskExtents+=disk.extents;
                                            totalDiskArrays++;
                                        }
                                    }
                                    if(totalDiskExtents<totalVirtualDisk) {
                                        SkipType.PRIMARY_PLUS_SECONDARY_DISK_EXTENTS_EXCEEDED.counter++;
                                        needsSkip = true;
                                        break;
                                    } else if((totalDiskArrays*1000)<totalVirtualDiskWeight) {
                                        SkipType.PRIMARY_PLUS_SECONDARY_DISK_WEIGHT_EXCEEDED.counter++;
                                        needsSkip = true;
                                        break;
                                    }
                                }
                                if(!needsSkip) {
                                    // Now try each of the possible secondary mappings (to all servers except the primary)
                                    for(int secondaryServerIndex=0; secondaryServerIndex<serversSize; secondaryServerIndex++) {
                                        if(secondaryServerIndex!=primaryServerIndex) {
                                            final Server secondaryServer = servers[secondaryServerIndex];

                                            // Allow for manual configuration of secondary
                                            if(virtualServer.secondaryServerHostname==null || virtualServer.secondaryServerHostname.equals(secondaryServer.hostname)) {
                                                // Make sure the secondary architecture matches any requirements
                                                if(
                                                    virtualServer.requiredProcessorArchitecture==null
                                                    || secondaryServer.processorArchitecture==virtualServer.requiredProcessorArchitecture
                                                ) {
                                                    // Make sure secondary has at least total number of cores matching secondary cores.
                                                    // Note: we don't care about weight here - just make it run somewhere when in failover.
                                                    if(secondaryServer.processorCores>=virtualServer.processorCores) {
                                                        // Make sure that the combined primary mapping plus secondary RAM does not exceed the total of this possible secondary machine
                                                        // for any one primary failure.  The loop represents the failure of each server, one at a time.
                                                        final int oldAllocatedSecondaryRAM = secondaryServer.allocatedSecondaryRAMs[primaryServerIndex];
                                                        final int newAllocatedSecondaryRAM = oldAllocatedSecondaryRAM + virtualServer.secondaryRam;
                                                        if(secondaryServer.ram >= (secondaryServer.allocatedPrimaryRAM+newAllocatedSecondaryRAM)) {
                                                            virtualServers[currentVirtualServer].selectedSecondaryServerIndex = secondaryServerIndex;
                                                            secondaryServer.allocatedSecondaryRAMs[primaryServerIndex] = newAllocatedSecondaryRAM;
                                                            final int oldMaximumAllocatedSecondaryRAM = secondaryServer.maximumAllocatedSecondaryRAM;
                                                            if(newAllocatedSecondaryRAM>oldMaximumAllocatedSecondaryRAM) secondaryServer.maximumAllocatedSecondaryRAM = newAllocatedSecondaryRAM;

                                                            // TODO: Dan: From here
                                                            // For each disk type, skip if exceeded on either total extents or arrays (allocation)
                                                            needsSkip = false;
                                                            for(int e=0;e<DiskType.diskTypes.length;e++) {
                                                                DiskType diskType = DiskType.diskTypes[e];
                                                                // Calculate total required for primaries
                                                                long totalVirtualDisk = 0;
                                                                long totalVirtualDiskWeight = 0;
                                                                for(int d=0;d<=currentVirtualServer;d++) {
                                                                    if(virtualServers[d].selectedPrimaryServerIndex==secondaryServerIndex) {
                                                                        VirtualServer mappedVirtualServer = virtualServers[d];
                                                                        for(VirtualDisk virtualDisk : mappedVirtualServer.virtualDisks) {
                                                                            if(virtualDisk.primaryDiskType==diskType) {
                                                                                totalVirtualDisk += virtualDisk.extents;
                                                                                totalVirtualDiskWeight += virtualDisk.primaryWeight;
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                                // Also add total required for secondaries
                                                                for(int d=0;d<=currentVirtualServer;d++) {
                                                                    if(virtualServers[d].selectedSecondaryServerIndex==secondaryServerIndex) {
                                                                        VirtualServer mappedVirtualServer = virtualServers[d];
                                                                        for(VirtualDisk virtualDisk : mappedVirtualServer.virtualDisks) {
                                                                            if(virtualDisk.secondaryDiskType==diskType) {
                                                                                totalVirtualDisk += virtualDisk.extents;
                                                                                totalVirtualDiskWeight += virtualDisk.secondaryWeight;
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                                // Calculate total provided by the secondaryServer
                                                                long totalDiskExtents = 0;
                                                                int totalDiskArrays = 0;
                                                                for(Disk disk : secondaryServer.disks) {
                                                                    if(disk.diskType==diskType) {
                                                                        totalDiskExtents+=disk.extents;
                                                                        totalDiskArrays++;
                                                                    }
                                                                }
                                                                if(totalDiskExtents<totalVirtualDisk) {
                                                                    needsSkip = true;
                                                                    SkipType.SECONDARY_DISK_EXTENTS_EXCEEDED.counter++;
                                                                    break;
                                                                } else if((totalDiskArrays*1000)<totalVirtualDiskWeight) {
                                                                    needsSkip = true;
                                                                    SkipType.SECONDARY_DISK_WEIGHT_EXCEEDED.counter++;
                                                                    break;
                                                                }
                                                            }
                                                            if(!needsSkip) {
                                                                mapServers(servers, virtualServers, currentVirtualServer+1);
                                                            } else {
                                                                skipped++;
                                                            }
                                                            // No need to reset: virtualServers[currentVirtualServer].selectedSecondaryServerIndex = -1;
                                                            secondaryServer.allocatedSecondaryRAMs[primaryServerIndex] = oldAllocatedSecondaryRAM;
                                                            secondaryServer.maximumAllocatedSecondaryRAM = oldMaximumAllocatedSecondaryRAM;
                                                        } else {
                                                            skipped++;
                                                            SkipType.SECONDARY_RAM_EXCEEDED.counter++;
                                                        }
                                                    } else {
                                                        skipped++;
                                                        SkipType.SECONDARY_CORES_EXCEEDED.counter++;
                                                    }
                                                } else {
                                                    skipped++;
                                                    SkipType.SECONDARY_PROCESSOR_MISMATCH.counter++;
                                                }
                                            } else {
                                                skipped++;
                                                SkipType.SECONDARY_MANUAL_SERVER_MISMATCH.counter++;
                                            }
                                        }
                                    }
                                } else {
                                    skipped++;
                                }
                                // No need to reset: virtualServers[currentVirtualServer].selectedPrimaryServerIndex = -1;
                                primaryServer.allocatedProcessorWeight = oldAllocatedProcessorWeight;
                                primaryServer.allocatedPrimaryRAM = oldAllocatedPrimaryRAM;
                            } else {
                                skipped++;
                                SkipType.PRIMARY_RAM_EXCEEDED.counter++;
                            }
                        } else {
                            skipped++;
                            SkipType.PRIMARY_CORES_EXCEEDED.counter++;
                        }
                    } else {
                        skipped++;
                        SkipType.PRIMARY_PROCESSOR_MISMATCH.counter++;
                    }
                } else {
                    skipped++;
                    SkipType.PRIMARY_MANUAL_SERVER_MISMATCH.counter++;
                }
            }
        }
    }
}
