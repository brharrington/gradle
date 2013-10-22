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

package org.gradle.api.tasks.diagnostics.internal.graph.nodes;

import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;

import java.util.Set;

public abstract class AbstractRenderableModuleResult implements RenderableDependency {

    protected final ResolvedComponentResult module;

    public AbstractRenderableModuleResult(ResolvedComponentResult module) {
        this.module = module;
    }

    public ModuleComponentIdentifier getId() {
        return module.getId();
    }

    public String getName() {
        return module.getId().getGroup() + ":" + module.getId().getName() + ":" + module.getId().getVersion();
    }

    public String getDescription() {
        return null;
    }

    public boolean isResolvable() {
        return true; // TODO
    }

    public abstract Set<RenderableDependency> getChildren();

    @Override
    public String toString() {
        return module.toString();
    }
}
