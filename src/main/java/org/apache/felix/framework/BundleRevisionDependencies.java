/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.framework;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.felix.framework.util.Util;
import org.apache.felix.framework.wiring.BundleWireImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

class BundleRevisionDependencies
{
    private final Map<BundleRevision, Map<BundleCapability, Set<BundleWire>>>
        m_dependentsMap = new HashMap<BundleRevision, Map<BundleCapability, Set<BundleWire>>>();

    public synchronized void addDependent(BundleWire bw)
    {
// TODO: OSGi R4.4 - Eventually we won't need to use the impl type here,
//       since the plan is to standardize on this method for the OBR spec.
        BundleRevision provider = ((BundleWireImpl) bw).getProvider();
        Map<BundleCapability, Set<BundleWire>> caps =
            m_dependentsMap.get(provider);
        if (caps == null)
        {
            caps = new HashMap<BundleCapability, Set<BundleWire>>();
            m_dependentsMap.put(provider, caps);
        }
        Set<BundleWire> dependents = caps.get(bw.getCapability());
        if (dependents == null)
        {
            dependents = new HashSet<BundleWire>();
            caps.put(bw.getCapability(), dependents);
        }
        dependents.add(bw);
    }

/*
    public synchronized void removeDependent(
        BundleRevision provider, BundleCapability cap, BundleRevision requirer)
    {
        Map<BundleCapability, Set<BundleRevision>> caps = m_dependentsMap.get(provider);
        if (caps != null)
        {
            Set<BundleRevision> dependents = caps.get(cap);
            if (dependents == null)
            {
                dependents.remove(requirer);
                if (dependents.isEmpty())
                {
                    caps.remove(cap);
                    if (caps.isEmpty())
                    {
                        m_dependentsMap.remove(provider);
                    }
                }
            }
        }
    }

    public synchronized void removeDependents(BundleRevision provider)
    {
        m_dependentsMap.remove(provider);
    }
*/
    public synchronized Map<BundleCapability, Set<BundleWire>>
        getDependents(BundleRevision provider)
    {
        return m_dependentsMap.get(provider);
    }

    public synchronized boolean hasDependents(BundleRevision revision)
    {
        // We have to special case fragments, since their dependencies
        // are actually reversed (i.e., they require a host, but then
        // the host ends up dependent on them at run time).
        if (Util.isFragment(revision)
            && (revision.getWiring() != null)
            && !revision.getWiring().getRequiredWires(null).isEmpty())
        {
            return true;
        }
        else if (m_dependentsMap.containsKey(revision))
        {
            return true;
        }
        return false;
    }

    public synchronized boolean hasDependents(BundleImpl bundle)
    {
        List<BundleRevision> revisions = bundle.getRevisions();
        for (BundleRevision revision : revisions)
        {
            if (hasDependents(revision))
            {
                return true;
            }
        }
        return false;
    }

    public synchronized List<BundleWire> getProvidedWires(
        BundleRevision revision, String namespace)
    {
        List<BundleWire> providedWires = new ArrayList<BundleWire>();

        Map<BundleCapability, Set<BundleWire>> caps =
            m_dependentsMap.get(revision);
        if (caps != null)
        {
            for (Entry<BundleCapability, Set<BundleWire>> entry : caps.entrySet())
            {
                if ((namespace == null) || entry.getKey().getNamespace().equals(namespace))
                {
                    providedWires.addAll(entry.getValue());
                }
            }
        }

        return providedWires;
    }

    public synchronized Set<Bundle> getDependentBundles(BundleImpl bundle)
    {
        Set<Bundle> result = new HashSet<Bundle>();

        List<BundleRevision> revisions = bundle.getRevisions();
        for (BundleRevision revision : revisions)
        {
// TODO: OSGi R4.3 - This is sort of a hack. We need to special case fragments,
//       since their dependents are their hosts.
            if (Util.isFragment(revision))
            {
                if (revision.getWiring() != null)
                {
                    for (BundleWire wire : revision.getWiring().getRequiredWires(null))
                    {
                        result.add(wire.getProviderWiring().getBundle());
                    }
                }
            }
            else
            {
                Map<BundleCapability, Set<BundleWire>> caps =
                    m_dependentsMap.get(revision);
                if (caps != null)
                {
                    for (Entry<BundleCapability, Set<BundleWire>> entry : caps.entrySet())
                    {
                        for (BundleWire dependentWire : entry.getValue())
                        {
// TODO: OSGi R4.4 - Eventually we won't need to use the impl type here,
//       since the plan is to standardize on this method for the OBR spec.
                            result.add(((BundleWireImpl) dependentWire)
                                .getRequirer().getBundle());
                        }
                    }
                }
            }
        }

        return result;
    }

    public synchronized Set<Bundle> getImportingBundles(
        BundleImpl exporter, BundleCapability exportCap)
    {
        // Create set for storing importing bundles.
        Set<Bundle> result = new HashSet<Bundle>();

        // Get exported package name.
        String pkgName = (String)
            exportCap.getAttributes().get(BundleRevision.PACKAGE_NAMESPACE);

        // Get all importers and requirers for all revisions of the bundle.
        // The spec says that require-bundle should be returned with importers.
        for (BundleRevision revision : exporter.getRevisions())
        {
            Map<BundleCapability, Set<BundleWire>>
                caps = m_dependentsMap.get(revision);
            if (caps != null)
            {
                for (Entry<BundleCapability, Set<BundleWire>> entry : caps.entrySet())
                {
                    BundleCapability cap = entry.getKey();
                    if ((cap.getNamespace().equals(BundleRevision.PACKAGE_NAMESPACE)
                        && cap.getAttributes().get(BundleRevision.PACKAGE_NAMESPACE)
                            .equals(pkgName))
                        || cap.getNamespace().equals(BundleRevision.BUNDLE_NAMESPACE))
                    {
                        for (BundleWire dependentWire : entry.getValue())
                        {
// TODO: OSGi R4.4 - Eventually we won't need to use the impl type here,
//       since the plan is to standardize on this method for the OBR spec.
                            result.add(((BundleWireImpl) dependentWire)
                                .getRequirer().getBundle());
                        }
                    }
                }
            }
        }

        // Return the results.
        return result;
    }

    public synchronized Set<Bundle> getRequiringBundles(BundleImpl bundle)
    {
        // Create set for storing requiring bundles.
        Set<Bundle> result = new HashSet<Bundle>();

        // Get all requirers for all revisions of the bundle.
        for (BundleRevision revision : bundle.getRevisions())
        {
            Map<BundleCapability, Set<BundleWire>>
                caps = m_dependentsMap.get(revision);
            if (caps != null)
            {
                for (Entry<BundleCapability, Set<BundleWire>> entry : caps.entrySet())
                {
                    if (entry.getKey().getNamespace()
                        .equals(BundleRevision.BUNDLE_NAMESPACE))
                    {
                        for (BundleWire dependentWire : entry.getValue())
                        {
// TODO: OSGi R4.4 - Eventually we won't need to use the impl type here,
//       since the plan is to standardize on this method for the OBR spec.
                            result.add(((BundleWireImpl) dependentWire)
                                .getRequirer().getBundle());
                        }
                    }
                }
            }
        }

        // Return the results.
        return result;
    }

    public synchronized void removeDependencies(BundleImpl bundle)
    {
        List<BundleRevision> revs = bundle.getRevisions();
        for (BundleRevision rev : revs)
        {
            BundleWiring wiring = rev.getWiring();
            if (wiring != null)
            {
                for (BundleWire wire : wiring.getRequiredWires(null))
                {
                    // The provider wiring may already be null if the framework
                    // is shutting down, so don't worry about updating dependencies
                    // in that case.
                    if (wire.getProviderWiring() != null)
                    {
                        Map<BundleCapability, Set<BundleWire>> caps =
                            m_dependentsMap.get(wire.getProviderWiring().getRevision());
                        if (caps != null)
                        {
                            List<BundleCapability> gc = new ArrayList<BundleCapability>();
                            for (Entry<BundleCapability, Set<BundleWire>> entry
                                : caps.entrySet())
                            {
                                entry.getValue().remove(wire);
                                if (entry.getValue().isEmpty())
                                {
                                    gc.add(entry.getKey());
                                }
                            }
                            for (BundleCapability cap : gc)
                            {
                                caps.remove(cap);
                            }
                            if (caps.isEmpty())
                            {
// TODO: OSGi R4.4 - Eventually we won't need to use the impl type here,
//       since the plan is to standardize on this method for the OBR spec.
                                m_dependentsMap.remove(((BundleWireImpl) wire).getProvider());
                            }
                        }
                    }
                }
            }
        }
    }

    public synchronized void dump()
    {
/*
System.out.println("DEPENDENTS:");
        for (Entry<BundleRevision, Map<BundleCapability, Set<BundleRevision>>> entry
            : m_dependentsMap.entrySet())
        {
            System.out.println("Revision " + entry.getKey() + " DEPS:");
            for (Entry<BundleCapability, Set<BundleRevision>> capEntry : entry.getValue().entrySet())
            {
                System.out.println("   " + capEntry.getKey() + " <-- " + capEntry.getValue());
            }
        }
*/
    }
}