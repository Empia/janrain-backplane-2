/*
 * Copyright 2011 Janrain, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.janrain.message;

import java.util.Map;

/**
 * A Map of String keys and values with an associated name. Can be saved easily into Amazon's SimpleDB.
 *
 * Concrete implementations must provide a nullary constructor, to be used before the pseudo-deserialization from a Map instance.
 * @see NamedMap#init(String, java.util.Map)
 *
 * @author Johnny Bufu
 */
public interface NamedMap extends Map<String,String> {

    /**
     * @return the name (identifier) for the map
     */
    String getName();

    /**
     * Initializes the named map from a Map instance and a name.
     *
     * Concrete implementations must provide a nullary constructor, for the pseudo-deserialization from a Map instance.
     *
     * @param name the named map's name
     * @param data the named map's data
     */
    void init(String name, Map<String,String> data);
}
