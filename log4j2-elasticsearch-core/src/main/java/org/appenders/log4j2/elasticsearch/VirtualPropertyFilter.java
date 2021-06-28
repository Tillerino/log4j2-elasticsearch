package org.appenders.log4j2.elasticsearch;

/*-
 * #%L
 * log4j2-elasticsearch
 * %%
 * Copyright (C) 2020 Rafal Foltynski
 * %%
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
 * #L%
 */

import org.apache.logging.log4j.core.LogEvent;

public interface VirtualPropertyFilter {

    /**
     * Allows to determine inclusion based on given field name and/or value
     *
     * @param fieldName {@link VirtualProperty#getName()}
     * @param value result of {@link ValueResolver#resolve(VirtualProperty, LogEvent)}
     *
     * @return <i>true</i>, if {@link VirtualProperty} should be included by {@link VirtualPropertiesWriter}, <i>false</i> otherwise
     */
    boolean isIncluded(String fieldName, String value);

}
