/*
 * Copyright 2011 Impetus Infotech.
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
package com.impetus.kundera.pelops;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.UUID;

import javax.persistence.PersistenceException;

import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.scale7.cassandra.pelops.Bytes;
import org.scale7.cassandra.pelops.Selector;

import com.impetus.kundera.Constants;
import com.impetus.kundera.client.PelopsClient;
import com.impetus.kundera.ejb.EntityManagerImpl;
import com.impetus.kundera.metadata.EmbeddedCollectionCacheHandler;
import com.impetus.kundera.metadata.EntityMetadata;
import com.impetus.kundera.property.PropertyAccessException;
import com.impetus.kundera.property.PropertyAccessorFactory;
import com.impetus.kundera.property.PropertyAccessorHelper;
import com.impetus.kundera.proxy.EnhancedEntity;
import com.impetus.kundera.utils.ReflectUtils;

/**
 * Provides Pelops utility methods for data held in Column family based stores   
 * @author amresh.singh
 */
public class PelopsDataHandler {
	private static Log log = LogFactory.getLog(PelopsDataHandler.class);
	
	/** The Constant TO_ONE_SUPER_COL_NAME. */
    private static final String TO_ONE_SUPER_COL_NAME = "FKey-TO";
    
    
    public  <E> E fromThriftRow(Selector selector, EntityManagerImpl em, Class<E> clazz, EntityMetadata m, String rowKey)
    	throws Exception {
    	List<String> superColumnNames = m.getSuperColumnFieldNames();   
    	E e = null;
    	if(! superColumnNames.isEmpty()) {        	
        	List<SuperColumn> thriftSuperColumns = selector.getSuperColumnsFromRow(m.getTableName(), rowKey, Selector.newColumnsPredicateAll(true, 10000), ConsistencyLevel.ONE);
        	System.out.println(thriftSuperColumns);
        	e = fromSuperColumnThriftRow(em, clazz, m, new PelopsClient().new ThriftRow(rowKey, m.getTableName(), null, thriftSuperColumns));
        	
        } else {
        	List<Column> columns = selector.getColumnsFromRow(m.getTableName(), new Bytes(rowKey.getBytes()),
                    Selector.newColumnsPredicateAll(true, 10), ConsistencyLevel.ONE);           
           
            e = fromColumnThriftRow(em, clazz, m, new PelopsClient().new ThriftRow(rowKey, m.getTableName(), columns, null));
                 	
        }   
    	return e;
    }
	
	/**
     * Fetches data held in Thrift row columns and populates to Entity objects    
     * @param <E> the element type
     * @param em the em
     * @param clazz the clazz
     * @param m the m
     * @param thriftRow the cr
     * @return the e
     * @throws Exception the exception
     */
    public  <E> E fromColumnThriftRow(EntityManagerImpl em, Class<E> clazz, EntityMetadata m, PelopsClient.ThriftRow thriftRow)
            throws Exception {

        // Instantiate a new instance
        E e = clazz.newInstance();

        // Set row-key. Note: @Id is always String.
        PropertyAccessorHelper.set(e, m.getIdProperty(), thriftRow.getId());

        // Iterate through each column
		for (Column c : thriftRow.getColumns()) {
            String name = PropertyAccessorFactory.STRING.fromBytes(c.getName());
            byte[] value = c.getValue();

			if (null == value) {
				continue;
			}

            // check if this is a property?
            EntityMetadata.Column column = m.getColumn(name);
			if (null == column) {
                // it could be some relational column
                EntityMetadata.Relation relation = m.getRelation(name);

				if (relation == null) {
					continue;
				}

                String foreignKeys = PropertyAccessorFactory.STRING.fromBytes(value);
                Set<String> keys = deserializeKeys(foreignKeys);
                em.getEntityResolver().populateForeignEntities(e, thriftRow.getId(), relation, keys.toArray(new String[0]));
			}

			else {
				try {
					PropertyAccessorHelper.set(e, column.getField(), value);
				} catch (PropertyAccessException pae) {
					log.warn(pae.getMessage());
				}
			}
        }

        return e;
    }
    
    
    /**
     * Fetches data held in Thrift row super columns and populates to Entity objects  
     */
	public <E> E fromSuperColumnThriftRow(EntityManagerImpl em, Class<E> clazz, EntityMetadata m, PelopsClient.ThriftRow tr) throws Exception {

		// Instantiate a new instance
		E e = clazz.newInstance();

		// Set row-key. Note: @Id is always String.
		PropertyAccessorHelper.set(e, m.getIdProperty(), tr.getId());

		// Get a name->field map for super-columns
		Map<String, Field> columnNameToFieldMap = new HashMap<String, Field>();
		Map<String, Field> superColumnNameToFieldMap = new HashMap<String, Field>();		
		createColumnsToFieldMaps(m, columnNameToFieldMap,
				superColumnNameToFieldMap);
		
		//Add all super columns to entity
		Collection embeddedCollection = null;
		Field embeddedCollectionField = null;
		for (SuperColumn sc : tr.getSuperColumns()) {
			String scName = PropertyAccessorFactory.STRING.fromBytes(sc.getName());
			String scNamePrefix = null;			
			
			//If this super column is variable in number (name#sequence format)
			if(scName.indexOf(Constants.SUPER_COLUMN_NAME_DELIMITER) != -1) {
				StringTokenizer st = new StringTokenizer(scName, Constants.SUPER_COLUMN_NAME_DELIMITER);
				if(st.hasMoreTokens()) {
					scNamePrefix = st.nextToken();
				}
				
				embeddedCollectionField = superColumnNameToFieldMap.get(scNamePrefix);
				Class embeddedCollectionFieldClass = embeddedCollectionField.getType();				
				
				if(embeddedCollection == null || embeddedCollection.isEmpty()) {
					if(embeddedCollectionFieldClass.equals(List.class)) {
						embeddedCollection = new ArrayList<Object>();
					} else if(embeddedCollectionFieldClass.equals(Set.class)) {					
						embeddedCollection = new HashSet<Object>();
					} else {
						throw new PersistenceException("Super Column " + scName + " doesn't match with entity which should have been a Collection");
					}
				}						
				
				Class<?> embeddedClass = PropertyAccessorHelper.getGenericClass(embeddedCollectionField);		
				
				// must have a default no-argument constructor
		        try {
		        	embeddedClass.getConstructor();
		        } catch (NoSuchMethodException nsme) {
		            throw new PersistenceException(embeddedClass.getName() + " is @Embeddable and must have a default no-argument constructor.");
		        }
				Object embeddedObject = embeddedClass.newInstance();
				
				
				for(Column column : sc.getColumns()) {
					String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
					byte[] value = column.getValue();
					if (value == null) {
						continue;
					}
					Field columnField = columnNameToFieldMap.get(name);
					PropertyAccessorHelper.set(embeddedObject, columnField, value);
				}
				embeddedCollection.add(embeddedObject);	
				
				//Add this embedded object to cache
				((PelopsClient)em.getClient()).getEcCacheHandler().addEmbeddedCollectionCacheMapping(tr.getId(), embeddedObject, scName);
				
				
			} else {
				Field superColumnField = superColumnNameToFieldMap.get(scName);
				Class superColumnClass = superColumnField.getType();
				Object superColumnObj = superColumnClass.newInstance();
				
				boolean intoRelations = false;
				if (scName.equals(TO_ONE_SUPER_COL_NAME)) {
					intoRelations = true;
				}

				for (Column column : sc.getColumns()) {
					String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
					byte[] value = column.getValue();

					if (value == null) {
						continue;
					}

					if (intoRelations) {
						EntityMetadata.Relation relation = m.getRelation(name);

						String foreignKeys = PropertyAccessorFactory.STRING
								.fromBytes(value);
						Set<String> keys = deserializeKeys(foreignKeys);
						em.getEntityResolver()
								.populateForeignEntities(e, tr.getId(), relation,
										keys.toArray(new String[0]));

					} else {
						// set value of the field in the bean
						Field columnField = columnNameToFieldMap.get(name);						
						
						PropertyAccessorHelper.set(superColumnObj, columnField, value);
					}
				}
				PropertyAccessorHelper.set(e, superColumnField, superColumnObj);			
			}
			
			
		}
		
		if(embeddedCollection != null && ! embeddedCollection.isEmpty()) {
			PropertyAccessorHelper.set(e, embeddedCollectionField, embeddedCollection);
		}
		return e;
	}
	
	
	public Object populateEmbeddedObject(SuperColumn sc, EntityMetadata m) throws Exception {		
		Field embeddedCollectionField = null;
		Object embeddedObject = null;
		String scName = PropertyAccessorFactory.STRING.fromBytes(sc.getName());
		String scNamePrefix = null;	
		
		// Get a name->field map for super-columns
		Map<String, Field> columnNameToFieldMap = new HashMap<String, Field>();
		Map<String, Field> superColumnNameToFieldMap = new HashMap<String, Field>();		
		createColumnsToFieldMaps(m, columnNameToFieldMap,
				superColumnNameToFieldMap);
				
		//If this super column is variable in number (name#sequence format)
		if(scName.indexOf(Constants.SUPER_COLUMN_NAME_DELIMITER) != -1) {
			StringTokenizer st = new StringTokenizer(scName, Constants.SUPER_COLUMN_NAME_DELIMITER);
			if(st.hasMoreTokens()) {
				scNamePrefix = st.nextToken();
			}			
			
			embeddedCollectionField = superColumnNameToFieldMap.get(scNamePrefix);			
			Class<?> embeddedClass = PropertyAccessorHelper.getGenericClass(embeddedCollectionField);				
			
			// must have a default no-argument constructor
	        try {
	        	embeddedClass.getConstructor();
	        } catch (NoSuchMethodException nsme) {
	            throw new PersistenceException(embeddedClass.getName() + " is @Embeddable and must have a default no-argument constructor.");
	        }
			embeddedObject = embeddedClass.newInstance();
			
			
			for(Column column : sc.getColumns()) {
				String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
				byte[] value = column.getValue();
				if (value == null) {
					continue;
				}
				Field columnField = columnNameToFieldMap.get(name);
				PropertyAccessorHelper.set(embeddedObject, columnField, value);
			}		
			
		} else {
			Field superColumnField = superColumnNameToFieldMap.get(scName);
			Class superColumnClass = superColumnField.getType();
			embeddedObject = superColumnClass.newInstance();
			

			for (Column column : sc.getColumns()) {
				String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
				byte[] value = column.getValue();

				if (value == null) {
					continue;
				}				
				// set value of the field in the bean
				Field columnField = columnNameToFieldMap.get(name);			
				PropertyAccessorHelper.set(embeddedObject, columnField, value);
			
			}						
		}
		return embeddedObject;
	}

    /**
     * Helper method to convert @Entity to ThriftRow.
     *
     * @param e the e
     * @param columnsLst the columns lst
     * @param columnFamily the colmun family
     * @return the base data accessor. thrift row
     * @throws Exception the exception
     */
    public PelopsClient.ThriftRow toThriftRow(PelopsClient client, EnhancedEntity e, EntityMetadata m, String columnFamily) 
    	throws Exception {    	
    	// timestamp to use in thrift column objects
        long timestamp = System.currentTimeMillis();

        PelopsClient.ThriftRow tr = new PelopsClient(). new ThriftRow();

        tr.setColumnFamilyName(columnFamily);	        			// column-family name       
        tr.setId(e.getId());										// Id
        
        addSuperColumnsToThriftRow(timestamp, client, tr, m, e);	//Super columns        
        addColumnsToThriftRow(timestamp, tr, m, e);				//Columns              
        
		return tr;
    }
        
    private void addColumnsToThriftRow(long timestamp, PelopsClient.ThriftRow tr, EntityMetadata m, EnhancedEntity e) throws Exception {
    	List<Column> columns = new ArrayList<Column>();
    	
        // Iterate through each column-meta and populate that with field values
        for (EntityMetadata.Column column : m.getColumnsAsList()) {
            Field field = column.getField();
            String name = column.getName();
            try {
                byte[] value = PropertyAccessorHelper.get(e.getEntity(), field);
                Column col = new Column();
                col.setName(PropertyAccessorFactory.STRING.toBytes(name));
                col.setValue(value);
                col.setTimestamp(timestamp);
                columns.add(col);
            } catch (PropertyAccessException exp) {
                log.warn(exp.getMessage());
            }

        }
        
        addForeignkeysToColumns(timestamp, e, columns);	
        tr.setColumns(columns);			
    }

	
    
    private void addSuperColumnsToThriftRow(long timestamp, PelopsClient client, PelopsClient.ThriftRow tr, EntityMetadata m, EnhancedEntity e) throws Exception {
    	 //Iterate through Super columns
        for (EntityMetadata.SuperColumn superColumn : m.getSuperColumnsAsList()) {            
            Field superColumnField = superColumn.getField();
            Object superColumnObject = PropertyAccessorHelper.getObject(e.getEntity(), superColumnField);
             
            
            //If Embedded object is a Collection, there will be variable number of super columns one for each object in collection.
            //Key for each super column will be of the format "<Embedded object field name>#<Unique sequence number>
            
            //On the other hand, if embedded object is not a Collection, it would simply be embedded as ONE super column.
            String superColumnName = null;
            if(superColumnObject == null) {
            	return;
            }
            if(superColumnObject instanceof Collection) {   
            	
            	EmbeddedCollectionCacheHandler ecCacheHandler = client.getEcCacheHandler();           	
    		
				// Check whether it's first time insert or updation
				if (ecCacheHandler.isCacheEmpty()) { // First time insert
					int count = 0;	            	
					for (Object obj : (Collection) superColumnObject) {
						superColumnName = superColumn.getName() + Constants.SUPER_COLUMN_NAME_DELIMITER + count;
						SuperColumn thriftSuperColumn = buildThriftSuperColumn(superColumnName, timestamp, superColumn, obj);
						tr.addSuperColumn(thriftSuperColumn);
						
						count++;
					}
				} else { // Updation
					// Check whether this object is already in cache, which means we already have a super column
					// Otherwise we need to generate a fresh super column name					
					int lastEmbeddedObjectCount = ecCacheHandler.getLastEmbeddedObjectCount(e.getId());
					for (Object obj : (Collection) superColumnObject) {							
						superColumnName = ecCacheHandler.getEmbeddedObjectName(e.getId(), obj);						
						if (superColumnName == null) { // Fresh row
							superColumnName = superColumn.getName() + Constants.SUPER_COLUMN_NAME_DELIMITER + (++lastEmbeddedObjectCount);							
						}
						SuperColumn thriftSuperColumn = buildThriftSuperColumn(superColumnName, timestamp, superColumn, obj);
						tr.addSuperColumn(thriftSuperColumn);
					}			
				}           	
            	
            } else {
            	superColumnName = superColumn.getName();
            	SuperColumn thriftSuperColumn = buildThriftSuperColumn(superColumnName, timestamp, superColumn, superColumnObject);
                tr.addSuperColumn(thriftSuperColumn);            	
            }         
            
        }
        
		// Add relations entities as Foreign keys to a new super column
		createForeignKeySuperColumn(timestamp, tr, e);
    }

	
    
    private SuperColumn buildThriftSuperColumn(String superColumnName, long timestamp, EntityMetadata.SuperColumn superColumn, Object superColumnObject) throws PropertyAccessException {
    	List<Column> thriftColumns = new ArrayList<Column>();  
    	for (EntityMetadata.Column column : superColumn.getColumns()) {
            Field field = column.getField();
            String name = column.getName();

            try {
                byte[] value = PropertyAccessorHelper.get(superColumnObject, field);
                if (null != value) {
                    Column thriftColumn = new Column();
                    thriftColumn.setName(PropertyAccessorFactory.STRING.toBytes(name));
                    thriftColumn.setValue(value);
                    thriftColumn.setTimestamp(timestamp);
                    thriftColumns.add(thriftColumn);
                }
            } catch (PropertyAccessException exp) {
                log.warn(exp.getMessage());
            }
        }
        SuperColumn thriftSuperColumn = new SuperColumn();     
        thriftSuperColumn.setName(PropertyAccessorFactory.STRING.toBytes(superColumnName));
        thriftSuperColumn.setColumns(thriftColumns);      
        
        return thriftSuperColumn;
    }
    
    
    /**
     * Splits foreign keys into Set. 
     * @param foreignKeys the foreign keys
     * @return the set
     */
	private Set<String> deserializeKeys(String foreignKeys) {
		Set<String> keys = new HashSet<String>();

		if (null == foreignKeys || foreignKeys.isEmpty()) {
			return keys;
		}

		String array[] = foreignKeys.split(Constants.SEPARATOR);
		for (String element : array) {
			keys.add(element);
		}
		return keys;
	}

    /**
     * Creates a string representation of a set of foreign keys by combining
     * them together separated by "~" character.
     * Note: Assumption is that @Id will never contain "~" character. Checks for
     * this are not added yet.
     * @param foreignKeys the foreign keys
     * @return the string
     */
	private String serializeKeys(Set<String> foreignKeys) {
		if (null == foreignKeys || foreignKeys.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();
		for (String key : foreignKeys) {
			if (sb.length() > 0) {
				sb.append(Constants.SEPARATOR);
			}
			sb.append(key);
		}
		return sb.toString();
    }
	
	/**
	 * @param m
	 * @param columnNameToFieldMap
	 * @param superColumnNameToFieldMap
	 */
	private void createColumnsToFieldMaps(EntityMetadata m,
			Map<String, Field> columnNameToFieldMap,
			Map<String, Field> superColumnNameToFieldMap) {
		for (Map.Entry<String, EntityMetadata.SuperColumn> entry : m
				.getSuperColumnsMap().entrySet()) {
			EntityMetadata.SuperColumn scMetadata = entry.getValue();
			superColumnNameToFieldMap.put(scMetadata.getName(), scMetadata.getField());
			for (EntityMetadata.Column cMetadata : entry.getValue()
					.getColumns()) {
				columnNameToFieldMap.put(cMetadata.getName(),
						cMetadata.getField());
			}
		}
	}
	
	/**
	 * All relationships in a column family are saved as additional column internally, one for each relationship entity.
     * Columns value is row key of relationship entity for 1-to-1 relationship
     * and ~ delimited row keys of relationship entities for 1-M relationship	 
	 * @throws PropertyAccessException
	 */
	public void addForeignkeysToColumns(long timestamp, EnhancedEntity e,
			List<Column> columns) throws PropertyAccessException {
		// Add relationships as foreign keys 
        for (Map.Entry<String, Set<String>> entry : e.getForeignKeysMap().entrySet()) {
            String property = entry.getKey();
            Set<String> foreignKeys = entry.getValue();

            String keys = serializeKeys(foreignKeys);
            if (null != keys) {
                Column col = new Column();

                col.setName(PropertyAccessorFactory.STRING.toBytes(property));
                col.setValue(PropertyAccessorFactory.STRING.toBytes(keys));
                col.setTimestamp(timestamp);
                columns.add(col);
            }
        }
	}
	
	/**
	 * For super column families, all relationships are saved as columns, in one additional super column 
	 * used internally.
	 * @throws PropertyAccessException
	 */
	public void createForeignKeySuperColumn(long timestamp, PelopsClient.ThriftRow tr, EnhancedEntity e)
			throws PropertyAccessException {
		List<Column> columns = new ArrayList<Column>();
		addForeignkeysToColumns(timestamp, e, columns);
		if (!columns.isEmpty()) {
			SuperColumn superCol = new SuperColumn();
			superCol.setName(PropertyAccessorFactory.STRING.toBytes(TO_ONE_SUPER_COL_NAME));
			superCol.setColumns(columns);
			tr.addSuperColumn(superCol);
		}
	}

}
