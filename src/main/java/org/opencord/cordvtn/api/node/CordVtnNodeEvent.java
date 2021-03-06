/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencord.cordvtn.api.node;

import org.onosproject.event.AbstractEvent;

/**
 * Describes cordvtn node events.
 */
public class CordVtnNodeEvent extends AbstractEvent<CordVtnNodeEvent.Type, CordVtnNode> {

    public enum Type {

        /**
         * Signifies that the new node is created.
         */
        NODE_CREATED,

        /**
         * Signifies that the node is updated.
         */
        NODE_UPDATED,

        /**
         * Signifies that the node is removed.
         */
        NODE_REMOVED,

        /**
         * Signifies that the node state is changed to complete.
         */
        NODE_COMPLETE,

        /**
         * Signifies that the node state is changed to incomplete.
         */
        NODE_INCOMPLETE
    }

    /**
     * Creates an event of a given type and the specified node.
     *
     * @param type cordvtn node event type
     * @param node cordvtn node subject
     */
    public CordVtnNodeEvent(Type type, CordVtnNode node) {
        super(type, node);
    }
}
