package io.stardog.stardao.dynamodb;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Index;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.DeleteItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.PutItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateGlobalSecondaryIndexAction;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteTableRequest;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndexDescription;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import io.stardog.stardao.core.AbstractDao;
import io.stardog.stardao.core.Results;
import io.stardog.stardao.core.Update;
import io.stardog.stardao.core.field.Field;
import io.stardog.stardao.dynamodb.mapper.ItemMapper;
import io.stardog.stardao.dynamodb.mapper.JacksonItemMapper;
import io.stardog.stardao.exceptions.DataNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public abstract class AbstractDynamoDao<M,K,I> extends AbstractDao<M,K,I> {
    protected final ItemMapper<M> mapper;
    protected final AmazonDynamoDB db;
    protected final Table table;
    protected final String tableName;
    private final static Logger LOGGER = LoggerFactory.getLogger(AbstractDynamoDao.class);

    public AbstractDynamoDao(Class<M> modelClass, AmazonDynamoDB db, String tableName) {
        super(modelClass);
        this.db = db;
        this.tableName = tableName;
        this.mapper = new JacksonItemMapper<>(modelClass, getFieldData());
        this.table = new DynamoDB(db).getTable(tableName);
    }

    public AbstractDynamoDao(Class<M> modelClass, AmazonDynamoDB db, String tableName, ItemMapper<M> mapper) {
        super(modelClass);
        this.db = db;
        this.tableName = tableName;
        this.mapper = mapper;
        this.table = new DynamoDB(db).getTable(tableName);
    }

    /**
     * Returns the full table name as it appears in DynamoDB.
     * @return  table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Returns the underlying DynamoDB table.
     * @return  DynamoDB Table object
     */
    public Table getTable() {
        return table;
    }

    /**
     * Returns the item mapper used to convert POJOs to Items and vice versa
     * @return  the item mapper
     */
    public ItemMapper<M> getMapper() {
        return mapper;
    }

    /**
     * Generate a new primary key. Defaults to generating random UUIDs. If you are not using UUIDs, you must override
     * this method.
     * @return  primary key value
     */
    protected Object generatePrimaryKeyValue() {
        return UUID.randomUUID();
    }

    /**
     * Convert an id to a DynamoDB PrimaryKey object. For most types (e.g. UUID), this means converting to string form.
     * @param id    id object
     * @return  id converted to a primary key
     */
    protected PrimaryKey toPrimaryKey(K id) {
        return new PrimaryKey(getFieldData().getId().getStorageName(), toStorageValue(id));
    }

    /**
     * Convert a value to a property that can be stored in DynamoDB.
     * @param val   value
     * @return  the value, possibly converted to string
     */
    protected Object toStorageValue(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number || val instanceof Collection) {
            return val;
        } else if (val instanceof Instant) {
            return ((Instant) val).toEpochMilli();
        } else {
            return val.toString();
        }
    }

    /**
     * Load an object by its primary key id.
     * @param id    id of the object
     * @return  optional containing the object, or empty if not found
     */
    public Optional<M> loadOpt(K id) {
        GetItemSpec spec = new GetItemSpec()
                .withPrimaryKey(toPrimaryKey(id));
        Item item = table.getItem(spec);
        return Optional.ofNullable(mapper.toObject(item));
    }

    /**
     * Load an object by a secondary index key / value. Intended to be called by wrapper methods in the subclass.
     * @param indexName name of the index to search
     * @param key   key attribute name
     * @param value value object
     * @return  object
     */
    protected M loadByIndex(String indexName, String key, Object value) {
        QuerySpec spec = new QuerySpec()
                .withKeyConditionExpression("#key = :value")
                .withNameMap(new NameMap().with("#key", key))
                .withValueMap(new ValueMap().with(":value", value));

        Index index = table.getIndex(indexName);
        ItemCollection<QueryOutcome> items = index.query(spec);
        for (Item i : items) {
            return mapper.toObject(i);
        }
        throw new DataNotFoundException(getDisplayModelName() + " not found: " + value);
    }

    /**
     * Iterate all records in the table.
     * @return  Iterator that lets you traverse all of the records in the table
     */
    @Override
    public Iterable<M> iterateAll() {
        return () -> new DynamoIterator<>(table.scan().iterator(), mapper);
    }

    /**
     * Scan all records in the table. Should only be attempted for tables known to be small.
     * @return  results containing all records in the table
     */
    public Results<M,K> scanAll() {
        return scan(new ScanSpec());
    }

    /**
     * Scan the table, given a spec containing conditions. Intended to be called by wrapper methods in subclass.
     * @param spec  spec for the scan
     * @return  results object containing the results of the scan
     */
    protected Results<M,K> scan(ScanSpec spec) {
        List<M> results = new ArrayList<>();
        for (Item item : table.scan(spec)) {
            results.add(mapper.toObject(item));
        }
        return Results.of(results);
    }

    /**
     * Query a particular index for results. Intended to be called by wrapper methods in subclass.
     * @param indexName name of the index to search
     * @param spec  spec for the query
     * @return  results object containing the results of the query
     */
    protected Results<M,K> findByIndex(String indexName, QuerySpec spec) {
        Index index = table.getIndex(indexName);
        List<M> results = new ArrayList<>();
        for (Item item : index.query(spec)) {
            results.add(mapper.toObject(item));
        }
        return Results.of(results);
    }

    /**
     * Check whether a field is unique by querying an index
     * @param indexName name of the index to search
     * @param field name of the field to search
     * @param value value of the field to match
     * @param excludeId exclude objects that match this id (can pass null)
     * @return  true if the field value is unique, false if it is not unique
     */
    protected boolean checkUniqueField(String indexName, String field, Object value, K excludeId) {
        Index index = table.getIndex(indexName);
        QuerySpec spec = new QuerySpec()
                .withKeyConditionExpression("#field = :value")
                .withNameMap(new NameMap()
                                .with("#field", field)
                )
                .withValueMap(new ValueMap()
                                .with(":value", value)
                );
        Object excludeIdValue = toStorageValue(excludeId);
        for (Item item : index.query(spec)) {
            Object itemId = toStorageValue(item.get(getFieldData().getId().getStorageName()));
            if (!itemId.equals(excludeIdValue)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Create a new object and add id field if needed
     * @param model model object to create
     * @param createAt timestamp of the creation
     * @param creatorId id of the creator
     * @return  newly created object, including added fields
     */
    @Override
    public M create(M model, Instant createAt, I creatorId) {
        Item item = toCreateItem(model, createAt, creatorId);
        PutItemSpec spec = new PutItemSpec()
                .withItem(item);
        if (getFieldData().getId() != null) {
            spec = spec.withConditionExpression("attribute_not_exists(#id)")
                    .withNameMap(new NameMap()
                                    .with("#id", getFieldData().getId().getStorageName())
                    );
        }
        table.putItem(spec);
        return mapper.toObject(item);
    }

    /**
     * Return a DynamoDB item from a model, possibly adding timestamp and user id fields
     * @param model
     * @param createAt
     * @param creatorId
     * @return
     */
    protected Item toCreateItem(M model, Instant createAt, I creatorId) {
        Item item = mapper.toItem(model);

        // add the @Id field (primary key)
        Field id = getFieldData().getId();
        if (id != null && item.get(id.getStorageName()) == null) {
            item.with(id.getStorageName(), toStorageValue(generatePrimaryKeyValue()));
        }

        // add the @CreatedAt and @CreatedBy fields
        Field createdByField = getFieldData().getCreatedBy();
        if (createdByField != null && item.get(createdByField.getStorageName()) == null && creatorId != null) {
            item.with(createdByField.getStorageName(), toStorageValue(creatorId));
        }
        Field createdAtField = getFieldData().getCreatedAt();
        if (createdAtField != null && item.get(createdAtField.getStorageName()) == null && createAt != null) {
            item.with(createdAtField.getStorageName(), toStorageValue(createAt));
        }

        return item;
    }

    /**
     * Update an existing object
     * @param id    id of object
     * @param update    update data
     * @param updateAt    update at
     * @param updaterId id of the user performing the update
     */
    public void update(K id, Update<M> update, Instant updateAt, I updaterId) {
        UpdateItemSpec spec = toUpdateItemSpec(id, update, updateAt, updaterId);
        table.updateItem(spec);
    }

    /**
     * Update an existing object, returning a model containing the state of the object before update
     * @param id    id of object
     * @param update    update data
     * @return  the model
     */
    public M updateAndReturn(K id, Update<M> update, Instant updateAt, I updaterId) {
        UpdateItemSpec spec = toUpdateItemSpec(id, update, updateAt, updaterId);
        spec = spec.withReturnValues(ReturnValue.ALL_OLD);
        UpdateItemOutcome outcome = table.updateItem(spec);
        Item item = outcome.getItem();
        if (item == null) {
            item = new Item();
        }
        return mapper.toObject(item);
    }

    /**
     * Convert an id and update object into an UpdateItemSpec
     * @param id    id of object
     * @param update    update data
     * @return  spec containing the DynamoDB update
     */
    protected UpdateItemSpec toUpdateItemSpec(K id, Update<M> update, Instant updateAt, I updaterId) {
        String updateExpression = "";
        NameMap nameMap = new NameMap();
        ValueMap valueMap = new ValueMap();

        Item setFields = mapper.toItem(update.getSetObject());

        // add the @UpdatedBy and @UpdatedAt fields
        Field updatedByField = getFieldData().getUpdatedBy();
        if (updatedByField != null && updaterId != null) {
            setFields = setFields.with(updatedByField.getStorageName(), toStorageValue(updaterId));
        }
        Field updatedAtField = getFieldData().getUpdatedAt();
        if (updatedAtField != null && updateAt != null) {
            setFields = setFields.with(updatedAtField.getStorageName(), toStorageValue(updateAt));
        }

        for (Map.Entry<String,Object> e : setFields.asMap().entrySet()) {
            String key = e.getKey();
            nameMap.put("#" + key, key);
            valueMap.put(":" + key, toStorageValue(e.getValue()));
            updateExpression += "#" + key + " = :" + key + ", ";
        }
        if (updateExpression.length() > 0) {
            updateExpression = "SET " + updateExpression.substring(0, updateExpression.length()-2);
        }

        if (update.getRemoveFields().size() > 0) {
            updateExpression += " REMOVE ";
            for (String key : update.getRemoveFields()) {
                nameMap.put("#" + key, key);
                updateExpression += "#" + key + ", ";
            }
            updateExpression = updateExpression.substring(0, updateExpression.length()-2);
        }
        UpdateItemSpec spec = new UpdateItemSpec()
                .withPrimaryKey(toPrimaryKey(id))
                .withUpdateExpression(updateExpression)
                .withNameMap(nameMap);
        if (!valueMap.isEmpty()) {
            spec = spec.withValueMap(valueMap);
        }
        return spec;
    }

    /**
     * Delete a particular object, by id.
     * @param id    object id
     */
    @Override
    public void delete(K id) {
        DeleteItemSpec spec = new DeleteItemSpec();
        spec.withPrimaryKey(toPrimaryKey(id));
        table.deleteItem(spec);
    }

    /**
     * Get the primary key schema definition for this table. You must override this in the subclass.
     * @return  list defining the key schema
     */
    public abstract List<KeySchemaElement> getKeySchema();

    /**
     * Get the attribute definitions for this table. You must override this in the subclass.
     * @return  list of attribute definitions
     */
    public abstract List<AttributeDefinition> getAttributeDefinitions();

    /**
     * Return the list of global secondary indexes for this table. You generally will override this in the subclass.
     * @return  list of global secondary indexes
     */
    public List<GlobalSecondaryIndex> getGlobalSecondaryIndexes() {
        return Arrays.asList();
    }

    /**
     * Create the table, if it does not already exist, and wait for it to be active.
     * @throws InterruptedException
     */
    public void initTable() {
        CreateTableRequest request = new CreateTableRequest()
                .withTableName(getTableName())
                .withKeySchema(getKeySchema())
                .withProvisionedThroughput(new ProvisionedThroughput(1L, 1L))
                .withAttributeDefinitions(getAttributeDefinitions());
        List<GlobalSecondaryIndex> gsi = getGlobalSecondaryIndexes();
        if (gsi.size() > 0) {
            request = request.withGlobalSecondaryIndexes(gsi);
        }
        TableUtils.createTableIfNotExists(db, request);
        try {
            table.waitForActive();
        } catch (InterruptedException e) {
            LOGGER.warn("InterruptedException while waiting for table to become active: " + getTableName(), e);
        }
        ensureIndexes();
    }

    /**
     * Delete the table, if it exists, and wait for it to finish deleting.
     */
    public void dropTable() {
        DeleteTableRequest request = new DeleteTableRequest()
                .withTableName(getTableName());
        TableUtils.deleteTableIfExists(db, request);
        try {
            table.waitForDelete();
        } catch (InterruptedException e) {
            LOGGER.warn("InterruptedException while waiting for table delete: " + getTableName(), e);
        }
    }

    /**
     * Copy all data from a source database and table into this table.
     * @param sourceDb  source database connection
     * @param sourceTable   source table name
     */
    public void copyTable(AmazonDynamoDB sourceDb, String sourceTable) {
        for (Item item : new DynamoDB(sourceDb).getTable(sourceTable).scan()) {
            table.putItem(item);
        }
    }

    /**
     * Ensure that the state of the global secondary indexes matches that of the Dao definition.
     */
    public void ensureIndexes() {
        TableDescription desc = table.describe();
        List<AttributeDefinition> actualAttribs = desc.getAttributeDefinitions();
        List<GlobalSecondaryIndexDescription> actualIndexes = desc.getGlobalSecondaryIndexes();

        List<AttributeDefinition> desiredAttribs = getAttributeDefinitions();
        List<GlobalSecondaryIndex> desiredIndexes = getGlobalSecondaryIndexes();
        for (GlobalSecondaryIndex index : desiredIndexes) {
            if (!checkIndex(actualIndexes, index)) {
                CreateGlobalSecondaryIndexAction create = new CreateGlobalSecondaryIndexAction()
                        .withIndexName(index.getIndexName())
                        .withKeySchema(index.getKeySchema())
                        .withProvisionedThroughput(index.getProvisionedThroughput())
                        .withProjection(index.getProjection());
                AttributeDefinition attrHash = getAttribute(desiredAttribs, index.getKeySchema(), KeyType.HASH);
                AttributeDefinition attrRange = getAttribute(desiredAttribs, index.getKeySchema(), KeyType.RANGE);
                Index created;
                if (attrRange == null) {
                    created = table.createGSI(create, attrHash);
                } else {
                    created = table.createGSI(create, attrHash, attrRange);
                }
                try {
                    created.waitForActive();
                } catch (InterruptedException e) {
                    LOGGER.warn("InterruptedException while waiting for index creation: " + created, e);
                }
            }
        }
    }

    /**
     * Given a list of all attributes, a key schema, and a key type, return the attribute that matches that type.
     * @param attribs   list of all attributes
     * @param keySchema list of key schema elements
     * @param type  key type
     * @return  attribute that matches the key schema type
     */
    protected final AttributeDefinition getAttribute(List<AttributeDefinition> attribs, List<KeySchemaElement> keySchema, KeyType type) {
        KeySchemaElement key = null;
        for (KeySchemaElement k : keySchema) {
            if (k.getKeyType().equals(type.toString())) {
                key = k;
            }
        }
        if (key == null) {
            return null;
        }
        for (AttributeDefinition attr : attribs) {
            if (attr.getAttributeName().equals(key.getAttributeName())) {
                return attr;
            }
        }
        return null;
    }

    /**
     * Check whether index appears in actualIndexes.
     *
     * @param actualIndexes list of actual indexes on the table
     * @param index index to look for
     * @return  true if the index appears in the list of indexes on the table, false otherwise
     */
    private boolean checkIndex(List<GlobalSecondaryIndexDescription> actualIndexes, GlobalSecondaryIndex index) {
        if (actualIndexes == null) {
            return false;
        }
        for (GlobalSecondaryIndexDescription indexDesc : actualIndexes) {
            if (indexDesc.getIndexName().equals(index.getIndexName())) {
                return true;
            }
        }
        return false;
    }
}
