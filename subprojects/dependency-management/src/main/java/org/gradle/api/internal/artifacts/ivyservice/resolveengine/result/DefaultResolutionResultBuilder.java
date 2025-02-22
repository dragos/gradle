/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.result;

import com.google.common.collect.ImmutableMap;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.UnresolvedDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.ComponentSelectionCause;
import org.gradle.api.artifacts.result.ComponentSelectionReason;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.ResolvedGraphDependency;
import org.gradle.api.internal.artifacts.result.DefaultResolutionResult;
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult;
import org.gradle.internal.Describables;
import org.gradle.internal.Factory;
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector;
import org.gradle.internal.resolve.ModuleVersionResolveException;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultResolutionResultBuilder implements ResolvedComponentVisitor {
    private static final DefaultComponentSelectionDescriptor DEPENDENCY_LOCKING = new DefaultComponentSelectionDescriptor(ComponentSelectionCause.CONSTRAINT, Describables.of("Dependency locking"));
    private final Map<Long, DefaultResolvedComponentResult> components = new HashMap<>();
    private final CachingDependencyResultFactory dependencyResultFactory = new CachingDependencyResultFactory();
    private AttributeContainer requestedAttributes;
    private Long id;
    private ComponentSelectionReason selectionReason;
    private ComponentIdentifier componentId;
    private ModuleVersionIdentifier moduleVersion;
    private String repoId;
    private final Map<Long, ResolvedVariantResult> selectedVariants = new LinkedHashMap<>();

    public static ResolutionResult empty(ModuleVersionIdentifier id, ComponentIdentifier componentIdentifier, AttributeContainer attributes) {
        DefaultResolutionResultBuilder builder = new DefaultResolutionResultBuilder();
        builder.setRequestedAttributes(attributes);
        builder.startVisitComponent(0L, ComponentSelectionReasons.root());
        builder.visitComponentDetails(componentIdentifier, id, null);
        builder.visitComponentVariants(Collections.emptyList());
        return builder.complete(0L);
    }

    public void setRequestedAttributes(AttributeContainer attributes) {
        requestedAttributes = attributes;
    }

    public ResolutionResult complete(Long rootId) {
        return new DefaultResolutionResult(new RootFactory(components.get(rootId)), requestedAttributes);
    }

    @Override
    public void startVisitComponent(Long id, ComponentSelectionReason selectionReason) {
        this.id = id;
        this.selectionReason = selectionReason;
        this.selectedVariants.clear();
    }

    @Override
    public void visitComponentDetails(ComponentIdentifier componentId, ModuleVersionIdentifier moduleVersion, @Nullable String repoId) {
        this.componentId = componentId;
        this.moduleVersion = moduleVersion;
        this.repoId = repoId;
    }

    @Override
    public void visitSelectedVariant(Long id, ResolvedVariantResult variant) {
        selectedVariants.put(id, variant);
    }

    @Override
    public void visitComponentVariants(List<ResolvedVariantResult> allVariants) {
        // The nodes in the graph represent variants (mostly) and multiple variants of a component may be included in the graph, so a given component may be visited multiple times
        if (!components.containsKey(id)) {
            components.put(id, new DefaultResolvedComponentResult(moduleVersion, selectionReason, componentId, ImmutableMap.copyOf(selectedVariants), allVariants, repoId));
        }
        selectedVariants.clear();
    }

    public void visitOutgoingEdges(Long fromComponentId, Collection<? extends ResolvedGraphDependency> dependencies) {
        DefaultResolvedComponentResult fromComponent = components.get(fromComponentId);
        for (ResolvedGraphDependency d : dependencies) {
            DependencyResult dependencyResult;
            ResolvedVariantResult fromVariant = fromComponent.getVariant(d.getFromVariant());
            if (fromVariant == null) {
                throw new IllegalStateException("Corrupt serialized resolution result. Cannot find variant (" + d.getFromVariant() + ") for " + (d.isConstraint() ? "constraint " : "") + fromComponent + " -> " + d.getRequested().getDisplayName());
            }
            if (d.getFailure() != null) {
                dependencyResult = dependencyResultFactory.createUnresolvedDependency(d.getRequested(), fromComponent, d.isConstraint(), d.getReason(), d.getFailure());
            } else {
                DefaultResolvedComponentResult selectedComponent = components.get(d.getSelected());
                if (selectedComponent == null) {
                    throw new IllegalStateException("Corrupt serialized resolution result. Cannot find selected component (" + d.getSelected() + ") for " + (d.isConstraint() ? "constraint " : "") + fromVariant + " -> " + d.getRequested().getDisplayName());
                }
                ResolvedVariantResult selectedVariant;
                if (d.getSelectedVariant() != null) {
                    selectedVariant = selectedComponent.getVariant(d.getSelectedVariant());
                    if (selectedVariant == null) {
                        throw new IllegalStateException("Corrupt serialized resolution result. Cannot find selected variant (" + d.getSelectedVariant() + ") for " + (d.isConstraint() ? "constraint " : "") + fromVariant + " -> " + d.getRequested().getDisplayName());
                    }
                } else {
                    selectedVariant = null;
                }
                dependencyResult = dependencyResultFactory.createResolvedDependency(d.getRequested(), fromComponent, selectedComponent, selectedVariant, d.isConstraint());
                selectedComponent.addDependent((ResolvedDependencyResult) dependencyResult);
            }
            fromComponent.addDependency(dependencyResult);
            fromComponent.associateDependencyToVariant(dependencyResult, fromVariant);
        }
    }

    public void addExtraFailures(Long rootId, Set<UnresolvedDependency> extraFailures) {
        DefaultResolvedComponentResult root = components.get(rootId);
        for (UnresolvedDependency failure : extraFailures) {
            ModuleVersionSelector failureSelector = failure.getSelector();
            ModuleComponentSelector failureComponentSelector = DefaultModuleComponentSelector.newSelector(failureSelector.getModule(), failureSelector.getVersion());
            root.addDependency(dependencyResultFactory.createUnresolvedDependency(failureComponentSelector, root, true,
                ComponentSelectionReasons.of(DEPENDENCY_LOCKING),
                new ModuleVersionResolveException(failureComponentSelector, () -> "Dependency lock state out of date", failure.getProblem())));
        }
    }

    private static class RootFactory implements Factory<ResolvedComponentResult> {
        private final DefaultResolvedComponentResult rootModule;

        public RootFactory(DefaultResolvedComponentResult rootModule) {
            this.rootModule = rootModule;
        }

        @Override
        public ResolvedComponentResult create() {
            return rootModule;
        }
    }
}
