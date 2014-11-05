package de.bwaldvogel.mongo.backend.memory;

import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bson.BSONObject;
import org.bson.BasicBSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;

import de.bwaldvogel.mongo.backend.Constants;
import de.bwaldvogel.mongo.backend.LimitedList;
import de.bwaldvogel.mongo.backend.MongoCollection;
import de.bwaldvogel.mongo.backend.Utils;
import de.bwaldvogel.mongo.backend.memory.index.UniqueIndex;
import de.bwaldvogel.mongo.exception.MongoServerError;
import de.bwaldvogel.mongo.exception.MongoServerException;
import de.bwaldvogel.mongo.exception.MongoSilentServerException;
import de.bwaldvogel.mongo.exception.NoSuchCommandException;
import de.bwaldvogel.mongo.wire.message.MongoDelete;
import de.bwaldvogel.mongo.wire.message.MongoInsert;
import de.bwaldvogel.mongo.wire.message.MongoQuery;
import de.bwaldvogel.mongo.wire.message.MongoUpdate;

public class MemoryDatabase extends CommonDatabase {

    private static final Logger log = LoggerFactory.getLogger(MemoryDatabase.class);

    private Map<String, MongoCollection> collections = new HashMap<String, MongoCollection>();
    private Map<Channel, List<BSONObject>> lastResults = new HashMap<Channel, List<BSONObject>>();

    private MemoryBackend backend;

    private MongoCollection namespaces;
    private MongoCollection indexes;

    public MemoryDatabase(MemoryBackend backend, String databaseName) throws MongoServerException {
        super(databaseName);
        this.backend = backend;
        namespaces = new MemoryNamespacesCollection(getDatabaseName());
        collections.put(namespaces.getCollectionName(), namespaces);

        indexes = new MemoryIndexesCollection(getDatabaseName());
        addNamespace(indexes);
    }

    private synchronized MongoCollection resolveCollection(String collectionName, boolean throwIfNotFound)
            throws MongoServerException {
        checkCollectionName(collectionName);
        MongoCollection collection = collections.get(collectionName);
        if (collection == null && throwIfNotFound) {
            throw new MongoServerException("ns not found");
        }
        return collection;
    }

    private void checkCollectionName(String collectionName) throws MongoServerException {

        if (collectionName.length() > Constants.MAX_NS_LENGTH)
            throw new MongoServerError(10080, "ns name too long, max size is " + Constants.MAX_NS_LENGTH);

        if (collectionName.isEmpty())
            throw new MongoServerError(16256, "Invalid ns [" + collectionName + "]");
    }

    @Override
    public boolean isEmpty() {
        return collections.isEmpty();
    }

    @Override
    public Iterable<BSONObject> handleQuery(MongoQuery query) throws MongoServerException {
        clearLastStatus(query.getChannel());
        String collectionName = query.getCollectionName();
        MongoCollection collection = resolveCollection(collectionName, false);
        if (collection == null) {
            return Collections.emptyList();
        }
        return collection.handleQuery(query.getQuery(), query.getNumberToSkip(), query.getNumberToReturn(),
                query.getReturnFieldSelector());
    }

    @Override
    public void handleClose(Channel channel) {
        lastResults.remove(channel);
    }

    protected synchronized void clearLastStatus(Channel channel) {
        List<BSONObject> results = lastResults.get(channel);
        if (results == null) {
            results = new LimitedList<BSONObject>(10);
            lastResults.put(channel, results);
        }
        results.add(null);
    }

    @Override
    public void handleInsert(MongoInsert insert) throws MongoServerException {
        Channel channel = insert.getChannel();
        String collectionName = insert.getCollectionName();
        final List<BSONObject> documents = insert.getDocuments();

        if (collectionName.equals(indexes.getCollectionName())) {
            for (BSONObject indexDescription : documents) {
                addIndex(indexDescription);
            }
        } else {
            try {
                insertDocuments(channel, collectionName, documents);
            } catch (MongoServerException e) {
                log.error("failed to insert {}", insert, e);
            }
        }
    }

    protected void addNamespace(MongoCollection collection) throws MongoServerException {
        collections.put(collection.getCollectionName(), collection);
        namespaces.addDocument(new BasicDBObject("name", collection.getFullName()));
    }

    @Override
    public void handleDelete(MongoDelete delete) throws MongoServerException {
        Channel channel = delete.getChannel();
        final String collectionName = delete.getCollectionName();
        final BSONObject selector = delete.getSelector();
        final int limit = delete.isSingleRemove() ? 1 : Integer.MAX_VALUE;

        try {
            deleteDocuments(channel, collectionName, selector, limit);
        } catch (MongoServerException e) {
            log.error("failed to delete {}", delete, e);
        }
    }

    @Override
    public void handleUpdate(MongoUpdate updateCommand) throws MongoServerException {
        final Channel channel = updateCommand.getChannel();
        final String collectionName = updateCommand.getCollectionName();
        final BSONObject selector = updateCommand.getSelector();
        final BSONObject update = updateCommand.getUpdate();
        final boolean multi = updateCommand.isMulti();
        final boolean upsert = updateCommand.isUpsert();

        try {
            BSONObject result = updateDocuments(channel, collectionName, selector, update, multi, upsert);
            putLastResult(channel, result);
        } catch (MongoServerException e) {
            log.error("failed to update {}", updateCommand, e);
        }
    }

    @Override
    public BSONObject handleCommand(Channel channel, String command, BSONObject query) throws MongoServerException {

        // getlasterror must not clear the last error
        if (command.equalsIgnoreCase("getnonce")) {
            return commandGetNonce(channel);
        } else if (command.equalsIgnoreCase("ping")) {
            return commandPing(channel);
        } else if (command.equalsIgnoreCase("logout")) {
            return commandLogout(channel);
        } else if (command.equalsIgnoreCase("authenticate")) {
            return commandAuthenticate(channel, command, query);
        } else if (command.equalsIgnoreCase("getlasterror")) {
            return commandGetLastError(channel, command, query);
        } else if (command.equalsIgnoreCase("getpreverror")) {
            return commandGetPrevError(channel, command, query);
        } else if (command.equalsIgnoreCase("reseterror")) {
            return commandResetError(channel, command, query);
        }

        clearLastStatus(channel);

        if (command.equalsIgnoreCase("insert")) {
            return commandInsert(channel, command, query);
        } else if (command.equalsIgnoreCase("update")) {
            return commandUpdate(channel, command, query);
        } else if (command.equalsIgnoreCase("delete")) {
            return commandDelete(channel, command, query);
        } else if (command.equalsIgnoreCase("createIndexes")) {
            return commandCreateIndexes(channel, command, query);
        } else if (command.equalsIgnoreCase("count")) {
            return commandCount(command, query);
        } else if (command.equalsIgnoreCase("distinct")) {
            String collectionName = query.get(command).toString();
            MongoCollection collection = resolveCollection(collectionName, true);
            return collection.handleDistinct(query);
        } else if (command.equalsIgnoreCase("drop")) {
            return commandDrop(query);
        } else if (command.equalsIgnoreCase("dropDatabase")) {
            return commandDropDatabase();
        } else if (command.equalsIgnoreCase("dbstats")) {
            return commandDatabaseStats();
        } else if (command.equalsIgnoreCase("collstats")) {
            String collectionName = query.get("collstats").toString();
            MongoCollection collection = resolveCollection(collectionName, true);
            return collection.getStats();
        } else if (command.equalsIgnoreCase("validate")) {
            String collectionName = query.get("validate").toString();
            MongoCollection collection = resolveCollection(collectionName, true);
            return collection.validate();
        } else if (command.equalsIgnoreCase("findAndModify")) {
            String collectionName = query.get(command).toString();
            MongoCollection collection = resolveOrCreateCollection(collectionName);
            return collection.findAndModify(query);
        } else {
            log.error("unknown query: {}", query);
        }
        throw new NoSuchCommandException(command);
    }

    private void addIndex(BSONObject indexDescription) throws MongoServerException {

        final String ns = indexDescription.get("ns").toString();
        final int index = ns.indexOf('.');
        final String collectionName = ns.substring(index + 1);
        final MongoCollection collection = resolveOrCreateCollection(collectionName);

        indexes.addDocument(indexDescription);

        BSONObject key = (BSONObject) indexDescription.get("key");
        if (key.keySet().equals(Collections.singleton("_id"))) {
            boolean ascending = Utils.normalizeValue(key.get("_id")).equals(Double.valueOf(1.0));
            collection.addIndex(new UniqueIndex("_id", ascending));
            log.info("adding unique _id index for collection {}", collectionName);
        } else if (Utils.isTrue(indexDescription.get("unique"))) {
            if (key.keySet().size() != 1) {
                throw new MongoServerException("Compound unique indices are not yet implemented");
            }

            log.info("adding unique index {} for collection {}", key.keySet(), collectionName);

            final String field = key.keySet().iterator().next();
            boolean ascending = Utils.normalizeValue(key.get(field)).equals(Double.valueOf(1.0));
            collection.addIndex(new UniqueIndex(field, ascending));
        } else {
            // TODO: non-unique non-id indexes not yet implemented
            log.warn("adding non-unique non-id index with key {} is not yet implemented", key);
        }
    }

    private MongoCollection createCollection(String collectionName) throws MongoServerException {
        checkCollectionName(collectionName);
        if (collectionName.contains("$")) {
            throw new MongoServerError(10093, "cannot insert into reserved $ collection");
        }
        MongoCollection collection = new MemoryCollection(getDatabaseName(), collectionName, "_id");
        addNamespace(collection);

        BSONObject indexDescription = new BasicBSONObject();
        indexDescription.put("name", "_id_");
        indexDescription.put("ns", collection.getFullName());
        indexDescription.put("key", new BasicBSONObject("_id", Integer.valueOf(1)));
        addIndex(indexDescription);

        log.info("created collection {}", collection.getFullName());

        return collection;
    }

    private BSONObject insertDocuments(final Channel channel, final String collectionName,
            final List<BSONObject> documents) throws MongoServerException {
        clearLastStatus(channel);
        try {
            if (collectionName.startsWith("system.")) {
                throw new MongoServerError(16459, "attempt to insert in system namespace");
            }
            final MongoCollection collection = resolveOrCreateCollection(collectionName);
            int n = collection.insertDocuments(documents);
            assert n == documents.size();
            final BSONObject result = new BasicBSONObject("n", Integer.valueOf(n));
            putLastResult(channel, result);
            return result;
        } catch (MongoServerError e) {
            putLastError(channel, e);
            throw e;
        }
    }

    private BSONObject deleteDocuments(final Channel channel, final String collectionName, final BSONObject selector,
            final int limit) throws MongoServerException {
        clearLastStatus(channel);
        try {
            if (collectionName.startsWith("system.")) {
                throw new MongoServerError(12050, "cannot delete from system namespace");
            }
            MongoCollection collection = resolveCollection(collectionName, false);
            int n;
            if (collection == null) {
                n = 0;
            } else {
                n = collection.deleteDocuments(selector, limit);
            }
            final BSONObject result = new BasicBSONObject("n", Integer.valueOf(n));
            putLastResult(channel, result);
            return result;
        } catch (MongoServerError e) {
            putLastError(channel, e);
            throw e;
        }
    }

    private BSONObject updateDocuments(final Channel channel, final String collectionName, final BSONObject selector,
            final BSONObject update, final boolean multi, final boolean upsert) throws MongoServerException {
        clearLastStatus(channel);
        try {
            if (collectionName.startsWith("system.")) {
                throw new MongoServerError(10156, "cannot update system collection");
            }

            MongoCollection collection = resolveOrCreateCollection(collectionName);
            return collection.updateDocuments(selector, update, multi, upsert);
        } catch (MongoServerException e) {
            putLastError(channel, e);
            throw e;
        }
    }

    private MongoCollection resolveOrCreateCollection(final String collectionName) throws MongoServerException {
        final MongoCollection collection = resolveCollection(collectionName, false);
        if (collection != null) {
            return collection;
        } else {
            return createCollection(collectionName);
        }
    }

    private BSONObject commandInsert(Channel channel, String command, BSONObject query) throws MongoServerException {
        String collectionName = query.get(command).toString();
        boolean isOrdered = Utils.isTrue(query.get("ordered"));
        if (!isOrdered)
            throw new RuntimeException("unexpected insert query: " + query);

        @SuppressWarnings("unchecked")
        List<BSONObject> documents = (List<BSONObject>) query.get("documents");

        List<BSONObject> writeErrors = new ArrayList<BSONObject>();
        int n = 0;
        for (BSONObject document : documents) {
            try {
                insertDocuments(channel, collectionName, Arrays.asList(document));
                n++;
            } catch (MongoServerError e) {
                BSONObject error = new BasicBSONObject();
                error.put("index", Integer.valueOf(n));
                error.put("code", Integer.valueOf(e.getCode()));
                error.put("errmsg", e.getMessage());
                writeErrors.add(error);
            }
        }
        BSONObject result = new BasicBSONObject();
        result.put("n", Integer.valueOf(n));
        if (!writeErrors.isEmpty()) {
            result.put("writeErrors", writeErrors);
        }
        // odd by true: also mark error as okay
        Utils.markOkay(result);
        return result;
    }

    private BSONObject commandUpdate(Channel channel, String command, BSONObject query) throws MongoServerException {
        String collectionName = query.get(command).toString();
        boolean isOrdered = Utils.isTrue(query.get("ordered"));
        if (!isOrdered)
            throw new RuntimeException("unexpected update query: " + query);

        @SuppressWarnings("unchecked")
        List<BSONObject> updates = (List<BSONObject>) query.get("updates");
        int n = 0;
        boolean updatedExisting = false;
        Collection<BSONObject> upserts = new ArrayList<BSONObject>();
        for (BSONObject updateObj : updates) {
            BSONObject selector = (BSONObject) updateObj.get("q");
            BSONObject update = (BSONObject) updateObj.get("u");
            boolean multi = Utils.isTrue(updateObj.get("multi"));
            boolean upsert = Utils.isTrue(updateObj.get("upsert"));
            final BSONObject result = updateDocuments(channel, collectionName, selector, update, multi, upsert);
            updatedExisting |= Utils.isTrue(result.get("updatedExisting"));
            if (result.containsField("upserted")) {
                final Object id = result.get("upserted");
                final BSONObject upserted = new BasicBSONObject("index", upserts.size());
                upserted.put("_id", id);
                upserts.add(upserted);
            }
            n += ((Integer) result.get("n")).intValue();
        }

        BSONObject response = new BasicBSONObject("n", Integer.valueOf(n));
        response.put("updatedExisting", Boolean.valueOf(updatedExisting));
        if (!upserts.isEmpty()) {
            response.put("upserted", upserts);
        }
        Utils.markOkay(response);
        putLastResult(channel, response);
        return response;
    }

    private BSONObject commandDelete(Channel channel, String command, BSONObject query) throws MongoServerException {
        String collectionName = query.get(command).toString();
        boolean isOrdered = Utils.isTrue(query.get("ordered"));
        if (!isOrdered)
            throw new RuntimeException("unexpected delete query: " + query);

        @SuppressWarnings("unchecked")
        List<BSONObject> deletes = (List<BSONObject>) query.get("deletes");
        int n = 0;
        for (BSONObject delete : deletes) {
            final BSONObject selector = (BSONObject) delete.get("q");
            final int limit = ((Number) delete.get("limit")).intValue();
            BSONObject result = deleteDocuments(channel, collectionName, selector, limit);
            n += ((Integer) result.get("n")).intValue();
        }

        BSONObject response = new BasicBSONObject("n", Integer.valueOf(n));
        Utils.markOkay(response);
        return response;
    }

    private BSONObject commandCreateIndexes(Channel channel, String command, BSONObject query)
            throws MongoServerException {

        int indexesBefore = indexes.count();

        @SuppressWarnings("unchecked")
        final Collection<BSONObject> indexDescriptions = (Collection<BSONObject>) query.get("indexes");
        for (BSONObject indexDescription : indexDescriptions) {
            addIndex(indexDescription);
        }

        int indexesAfter = indexes.count();

        BSONObject response = new BasicBSONObject();
        response.put("numIndexesBefore", Integer.valueOf(indexesBefore));
        response.put("numIndexesAfter", Integer.valueOf(indexesAfter));
        Utils.markOkay(response);
        return response;
    }

    private BSONObject commandDatabaseStats() throws MongoServerException {
        BSONObject response = new BasicBSONObject("db", getDatabaseName());
        response.put("collections", Integer.valueOf(namespaces.count()));

        long indexSize = 0;
        long objects = 0;
        long dataSize = 0;
        double averageObjectSize = 0;

        for (MongoCollection collection : collections.values()) {
            BSONObject stats = collection.getStats();
            objects += ((Number) stats.get("count")).longValue();
            dataSize += ((Number) stats.get("size")).longValue();

            BSONObject indexSizes = (BSONObject) stats.get("indexSize");
            for (String indexName : indexSizes.keySet()) {
                indexSize += ((Number) indexSizes.get(indexName)).longValue();
            }

        }
        if (objects > 0) {
            averageObjectSize = dataSize / ((double) objects);
        }
        response.put("objects", Long.valueOf(objects));
        response.put("avgObjSize", Double.valueOf(averageObjectSize));
        response.put("dataSize", Long.valueOf(dataSize));
        response.put("storageSize", Long.valueOf(0));
        response.put("numExtents", Integer.valueOf(0));
        response.put("indexes", Integer.valueOf(indexes.count()));
        response.put("indexSize", Long.valueOf(indexSize));
        response.put("fileSize", Integer.valueOf(0));
        response.put("nsSizeMB", Integer.valueOf(0));
        Utils.markOkay(response);
        return response;
    }

    private BSONObject commandDropDatabase() {
        backend.dropDatabase(this);
        BSONObject response = new BasicBSONObject("dropped", getDatabaseName());
        Utils.markOkay(response);
        return response;
    }

    private BSONObject commandDrop(BSONObject query) throws MongoServerException {
        String collectionName = query.get("drop").toString();
        MongoCollection collection = collections.remove(collectionName);

        if (collection == null) {
            throw new MongoSilentServerException("ns not found");
        }
        BSONObject response = new BasicBSONObject();
        namespaces.removeDocument(new BasicBSONObject("name", collection.getFullName()));
        response.put("nIndexesWas", Integer.valueOf(collection.getNumIndexes()));
        response.put("ns", collection.getFullName());
        Utils.markOkay(response);
        return response;

    }

    private void putLastError(Channel channel, MongoServerException ex) {
        BSONObject error = new BasicBSONObject();
        if (ex instanceof MongoServerError) {
            MongoServerError err = (MongoServerError) ex;
            error.put("err", err.getMessage());
            error.put("code", Integer.valueOf(err.getCode()));
        } else {
            error.put("err", ex.getMessage());
        }
        // TODO: https://github.com/netty/netty/issues/1810
        // also note:
        // http://stackoverflow.com/questions/17690094/channel-id-has-been-removed-in-netty4-0-final-version-how-can-i-solve
        error.put("connectionId", Integer.valueOf(channel.hashCode()));
        putLastResult(channel, error);
    }

    private synchronized void putLastResult(Channel channel, BSONObject result) {
        List<BSONObject> results = lastResults.get(channel);
        // list must not be empty
        BSONObject last = results.get(results.size() - 1);
        if (last != null) {
            throw new IllegalStateException("last result already set: " + last);
        }
        results.set(results.size() - 1, result);
    }

    //fake nonce calculation to static value based on example in docs
    private BSONObject commandGetNonce(Channel channel) {
        BSONObject response = new BasicBSONObject("nonce", "7ca422a24f326f2a");
        Utils.markOkay(response);
        return response;
    }

    //fake authenticate to always succeed
    private BSONObject commandAuthenticate(Channel channel, String command, BSONObject query)
            throws MongoServerException {
        BSONObject response = new BasicBSONObject();
        Utils.markOkay(response);
        return response;
    }

    private BSONObject commandLogout(Channel channel) {
        BSONObject response = new BasicBSONObject();
        Utils.markOkay(response);
        return response;
    }
    
    private BSONObject commandPing(Channel channel) {
        BSONObject response = new BasicBSONObject();
        Utils.markOkay(response);
        return response;
    }
    
    private BSONObject commandGetLastError(Channel channel, String command, BSONObject query)
            throws MongoServerException {
        Iterator<String> it = query.keySet().iterator();
        String cmd = it.next();
        if (!cmd.equals(command))
            throw new IllegalStateException();
        if (it.hasNext()) {
            String subCommand = it.next();
            if (subCommand.equals("w")) {
                // ignore
            } else if (subCommand.equals("fsync")) {
                // ignore
            } else {
                throw new MongoServerException("unknown subcommand: " + subCommand);
            }
        }

        List<BSONObject> results = lastResults.get(channel);

        BSONObject result = null;
        if (results != null && !results.isEmpty()) {
            result = results.get(results.size() - 1);
            if (result == null) {
                result = new BasicBSONObject();
            }
        } else {
            result = new BasicBSONObject();
            result.put("err", null);
        }
        Utils.markOkay(result);
        return result;
    }

    private BSONObject commandGetPrevError(Channel channel, String command, BSONObject query) {
        List<BSONObject> results = lastResults.get(channel);

        if (results != null) {
            for (int i = 1; i < results.size(); i++) {
                BSONObject result = results.get(results.size() - i);
                if (result == null) {
                    continue;
                }

                boolean isRelevant = false;
                if (result.get("err") != null) {
                    isRelevant = true;
                } else if (((Number) result.get("n")).intValue() > 0) {
                    isRelevant = true;
                }

                if (isRelevant) {
                    result.put("nPrev", Integer.valueOf(i));
                    return result;
                }
            }
        }

        // found no prev error
        BSONObject result = new BasicBSONObject();
        result.put("nPrev", Integer.valueOf(-1));
        Utils.markOkay(result);
        return result;
    }

    private BSONObject commandResetError(Channel channel, String command, BSONObject query) {
        List<BSONObject> results = lastResults.get(channel);
        if (results != null) {
            results.clear();
        }
        BSONObject result = new BasicBSONObject();
        Utils.markOkay(result);
        return result;
    }

    private BSONObject commandCount(String command, BSONObject query) throws MongoServerException {
        String collection = query.get(command).toString();
        BSONObject response = new BasicBSONObject();
        MongoCollection coll = collections.get(collection);
        if (coll == null) {
            response.put("missing", Boolean.TRUE);
            response.put("n", Integer.valueOf(0));
        } else {
            response.put("n", Integer.valueOf(coll.count((BSONObject) query.get("query"))));
        }
        Utils.markOkay(response);
        return response;
    }
}
