/*******************************************************************************
 * * Copyright 2011 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.kundera.client;

import java.util.HashMap;
import java.util.Map;


/**
 * The Enum ClientType.
 *
 * @author impetus
 */
public enum ClientType
{
    
    /** The HBASE. */
    HBASE, 
 /** The PELOPS. */
 PELOPS, 
 /** The MONGODB. */
 MONGODB, 
 /** The THRIFT. */
 THRIFT, 
 /** The RDBMS. */
 RDBMS,
 /** The Oracle KVStore. */
 KVSTORE;
    
    /** The coll. */
    static Map<String, ClientType> coll = new HashMap<String, ClientType>();

    /**
     * Static initialisation.
     */
    static
    {
        coll.put(HBASE.name(), HBASE);
        coll.put(PELOPS.name(), PELOPS);
        coll.put(THRIFT.name(), THRIFT);
        coll.put(MONGODB.name(), MONGODB);
        coll.put(RDBMS.name(), RDBMS);
        coll.put(KVSTORE.name(), KVSTORE);
    }

    /**
     * Returns value of clientType.
     *
     * @param clientType client type
     * @return clientType enum value.
     */
    public static ClientType getValue(String clientType)
    {
        if (clientType == null)
        {
            throw new EnumConstantNotPresentException(null, clientType);
        }
        return coll.get(clientType);
    }
}
