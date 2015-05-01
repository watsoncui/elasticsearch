/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Filter;
import org.elasticsearch.ElasticsearchGenerationException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.compress.CompressedString;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.StringAndBytesText;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.mapper.Mapping.SourceTransform;
import org.elasticsearch.index.mapper.internal.*;
import org.elasticsearch.index.mapper.object.ObjectMapper;
import org.elasticsearch.index.mapper.object.RootObjectMapper;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 *
 */
public class DocumentMapper implements ToXContent {

    /**
     * A listener to be called during the parse process.
     */
    public static interface ParseListener<ParseContext> {

        public static final ParseListener EMPTY = new ParseListenerAdapter();

        /**
         * Called before a field is added to the document. Return <tt>true</tt> to include
         * it in the document.
         */
        boolean beforeFieldAdded(FieldMapper fieldMapper, Field fieldable, ParseContext parseContent);
    }

    public static class ParseListenerAdapter implements ParseListener {

        @Override
        public boolean beforeFieldAdded(FieldMapper fieldMapper, Field fieldable, Object parseContext) {
            return true;
        }
    }

    public static class Builder {

        private Map<Class<? extends RootMapper>, RootMapper> rootMappers = new LinkedHashMap<>();

        private List<SourceTransform> sourceTransforms = new ArrayList<>(1);

        private final String index;

        private final Settings indexSettings;

        private final RootObjectMapper rootObjectMapper;

        private ImmutableMap<String, Object> meta = ImmutableMap.of();

        private final Mapper.BuilderContext builderContext;

        public Builder(String index, Settings indexSettings, RootObjectMapper.Builder builder) {
            this.index = index;
            this.indexSettings = indexSettings;
            this.builderContext = new Mapper.BuilderContext(indexSettings, new ContentPath(1));
            this.rootObjectMapper = builder.build(builderContext);

            // UID first so it will be the first stored field to load (so will benefit from "fields: []" early termination
            this.rootMappers.put(UidFieldMapper.class, new UidFieldMapper(indexSettings));
            this.rootMappers.put(IdFieldMapper.class, new IdFieldMapper(indexSettings));
            this.rootMappers.put(RoutingFieldMapper.class, new RoutingFieldMapper(indexSettings));
            // add default mappers, order is important (for example analyzer should come before the rest to set context.analyzer)
            this.rootMappers.put(SizeFieldMapper.class, new SizeFieldMapper(indexSettings));
            this.rootMappers.put(IndexFieldMapper.class, new IndexFieldMapper(indexSettings));
            this.rootMappers.put(SourceFieldMapper.class, new SourceFieldMapper(indexSettings));
            this.rootMappers.put(TypeFieldMapper.class, new TypeFieldMapper(indexSettings));
            this.rootMappers.put(AllFieldMapper.class, new AllFieldMapper(indexSettings));
            this.rootMappers.put(TimestampFieldMapper.class, new TimestampFieldMapper(indexSettings));
            this.rootMappers.put(TTLFieldMapper.class, new TTLFieldMapper(indexSettings));
            this.rootMappers.put(VersionFieldMapper.class, new VersionFieldMapper(indexSettings));
            this.rootMappers.put(ParentFieldMapper.class, new ParentFieldMapper(indexSettings));
            // _field_names last so that it can see all other fields
            this.rootMappers.put(FieldNamesFieldMapper.class, new FieldNamesFieldMapper(indexSettings));
        }

        public Builder meta(ImmutableMap<String, Object> meta) {
            this.meta = meta;
            return this;
        }

        public Builder put(RootMapper.Builder mapper) {
            RootMapper rootMapper = (RootMapper) mapper.build(builderContext);
            rootMappers.put(rootMapper.getClass(), rootMapper);
            return this;
        }

        public Builder transform(ScriptService scriptService, String script, ScriptType scriptType, String language, Map<String, Object> parameters) {
            sourceTransforms.add(new ScriptTransform(scriptService, script, scriptType, language, parameters));
            return this;
        }

        public DocumentMapper build(DocumentMapperParser docMapperParser) {
            Preconditions.checkNotNull(rootObjectMapper, "Mapper builder must have the root object mapper set");
            return new DocumentMapper(index, indexSettings, docMapperParser, rootObjectMapper, meta, rootMappers, sourceTransforms);
        }
    }

    private final String type;
    private final StringAndBytesText typeText;

    private volatile CompressedString mappingSource;

    private final Mapping mapping;

    private final DocumentParser documentParser;

    private volatile DocumentFieldMappers fieldMappers;

    private volatile ImmutableMap<String, ObjectMapper> objectMappers = ImmutableMap.of();

    private final List<FieldMapperListener> fieldMapperListeners = new CopyOnWriteArrayList<>();

    private final List<ObjectMapperListener> objectMapperListeners = new CopyOnWriteArrayList<>();

    private boolean hasNestedObjects = false;

    private final Filter typeFilter;

    private final Object mappersMutex = new Object();

    public DocumentMapper(String index, @Nullable Settings indexSettings, DocumentMapperParser docMapperParser,
                          RootObjectMapper rootObjectMapper,
                          ImmutableMap<String, Object> meta,
                          Map<Class<? extends RootMapper>, RootMapper> rootMappers, List<SourceTransform> sourceTransforms) {
        this.type = rootObjectMapper.name();
        this.typeText = new StringAndBytesText(this.type);
        this.mapping = new Mapping(
                rootObjectMapper,
                rootMappers.values().toArray(new RootMapper[rootMappers.values().size()]),
                sourceTransforms.toArray(new SourceTransform[sourceTransforms.size()]),
                meta);
        this.documentParser = new DocumentParser(index, indexSettings, docMapperParser, this);

        this.typeFilter = typeMapper().termFilter(type, null);

        if (rootMapper(ParentFieldMapper.class).active()) {
            // mark the routing field mapper as required
            rootMapper(RoutingFieldMapper.class).markAsRequired();
        }

        FieldMapperListener.Aggregator fieldMappersAgg = new FieldMapperListener.Aggregator();
        for (RootMapper rootMapper : this.mapping.rootMappers) {
            if (rootMapper instanceof FieldMapper) {
                fieldMappersAgg.mappers.add((FieldMapper) rootMapper);
            }
        }

        // now traverse and get all the statically defined ones
        rootObjectMapper.traverse(fieldMappersAgg);

        this.fieldMappers = new DocumentFieldMappers(docMapperParser.analysisService).copyAndAllAll(fieldMappersAgg.mappers);

        final Map<String, ObjectMapper> objectMappers = Maps.newHashMap();
        rootObjectMapper.traverse(new ObjectMapperListener() {
            @Override
            public void objectMapper(ObjectMapper objectMapper) {
                objectMappers.put(objectMapper.fullPath(), objectMapper);
            }
        });
        this.objectMappers = ImmutableMap.copyOf(objectMappers);
        for (ObjectMapper objectMapper : objectMappers.values()) {
            if (objectMapper.nested().isNested()) {
                hasNestedObjects = true;
            }
        }

        refreshSource();
    }

    public Mapping mapping() {
        return mapping;
    }

    public String type() {
        return this.type;
    }

    public Text typeText() {
        return this.typeText;
    }

    public ImmutableMap<String, Object> meta() {
        return mapping.meta;
    }

    public CompressedString mappingSource() {
        return this.mappingSource;
    }

    public RootObjectMapper root() {
        return mapping.root;
    }

    public UidFieldMapper uidMapper() {
        return rootMapper(UidFieldMapper.class);
    }

    @SuppressWarnings({"unchecked"})
    public <T extends RootMapper> T rootMapper(Class<T> type) {
        return mapping.rootMapper(type);
    }

    public IndexFieldMapper indexMapper() {
        return rootMapper(IndexFieldMapper.class);
    }

    public TypeFieldMapper typeMapper() {
        return rootMapper(TypeFieldMapper.class);
    }

    public SourceFieldMapper sourceMapper() {
        return rootMapper(SourceFieldMapper.class);
    }

    public AllFieldMapper allFieldMapper() {
        return rootMapper(AllFieldMapper.class);
    }

    public IdFieldMapper idFieldMapper() {
        return rootMapper(IdFieldMapper.class);
    }

    public RoutingFieldMapper routingFieldMapper() {
        return rootMapper(RoutingFieldMapper.class);
    }

    public ParentFieldMapper parentFieldMapper() {
        return rootMapper(ParentFieldMapper.class);
    }

    public SizeFieldMapper sizeFieldMapper() {
        return rootMapper(SizeFieldMapper.class);
    }

    public TimestampFieldMapper timestampFieldMapper() {
        return rootMapper(TimestampFieldMapper.class);
    }

    public TTLFieldMapper TTLFieldMapper() {
        return rootMapper(TTLFieldMapper.class);
    }

    public IndexFieldMapper IndexFieldMapper() {
        return rootMapper(IndexFieldMapper.class);
    }

    public SizeFieldMapper SizeFieldMapper() {
        return rootMapper(SizeFieldMapper.class);
    }

    public Filter typeFilter() {
        return this.typeFilter;
    }

    public boolean hasNestedObjects() {
        return hasNestedObjects;
    }

    public DocumentFieldMappers mappers() {
        return this.fieldMappers;
    }

    public ImmutableMap<String, ObjectMapper> objectMappers() {
        return this.objectMappers;
    }

    public ParsedDocument parse(BytesReference source) throws MapperParsingException {
        return parse(SourceToParse.source(source));
    }

    public ParsedDocument parse(String type, String id, BytesReference source) throws MapperParsingException {
        return parse(SourceToParse.source(source).type(type).id(id));
    }

    public ParsedDocument parse(SourceToParse source) throws MapperParsingException {
        return documentParser.parseDocument(source, null);
    }

    // NOTE: do not use this method, it will be removed in the future once
    // https://github.com/elastic/elasticsearch/issues/10736 is done (MLT api is the only user of this listener)
    public ParsedDocument parse(SourceToParse source, @Nullable ParseListener listener) throws MapperParsingException {
        return documentParser.parseDocument(source, listener);
    }

    /**
     * Returns the best nested {@link ObjectMapper} instances that is in the scope of the specified nested docId.
     */
    public ObjectMapper findNestedObjectMapper(int nestedDocId, SearchContext sc, LeafReaderContext context) throws IOException {
        ObjectMapper nestedObjectMapper = null;
        for (ObjectMapper objectMapper : objectMappers().values()) {
            if (!objectMapper.nested().isNested()) {
                continue;
            }

            Filter filter = sc.filterCache().cache(objectMapper.nestedTypeFilter(), null, sc.queryParserService().autoFilterCachePolicy());
            if (filter == null) {
                continue;
            }
            // We can pass down 'null' as acceptedDocs, because nestedDocId is a doc to be fetched and
            // therefor is guaranteed to be a live doc.
            DocIdSet nestedTypeSet = filter.getDocIdSet(context, null);
            if (nestedTypeSet == null) {
                continue;
            }
            DocIdSetIterator iterator = nestedTypeSet.iterator();
            if (iterator == null) {
                continue;
            }

            if (iterator.advance(nestedDocId) == nestedDocId) {
                if (nestedObjectMapper == null) {
                    nestedObjectMapper = objectMapper;
                } else {
                    if (nestedObjectMapper.fullPath().length() < objectMapper.fullPath().length()) {
                        nestedObjectMapper = objectMapper;
                    }
                }
            }
        }
        return nestedObjectMapper;
    }

    /**
     * Returns the parent {@link ObjectMapper} instance of the specified object mapper or <code>null</code> if there
     * isn't any.
     */
    // TODO: We should add: ObjectMapper#getParentObjectMapper()
    public ObjectMapper findParentObjectMapper(ObjectMapper objectMapper) {
        int indexOfLastDot = objectMapper.fullPath().lastIndexOf('.');
        if (indexOfLastDot != -1) {
            String parentNestObjectPath = objectMapper.fullPath().substring(0, indexOfLastDot);
            return objectMappers().get(parentNestObjectPath);
        } else {
            return null;
        }
    }

    /**
     * Transform the source when it is expressed as a map.  This is public so it can be transformed the source is loaded.
     * @param sourceAsMap source to transform.  This may be mutated by the script.
     * @return transformed version of transformMe.  This may actually be the same object as sourceAsMap
     */
    public Map<String, Object> transformSourceAsMap(Map<String, Object> sourceAsMap) {
        return DocumentParser.transformSourceAsMap(mapping, sourceAsMap);
    }

    public void addFieldMappers(Collection<FieldMapper<?>> fieldMappers) {
        synchronized (mappersMutex) {
            this.fieldMappers = this.fieldMappers.copyAndAllAll(fieldMappers);
        }
        for (FieldMapperListener listener : fieldMapperListeners) {
            listener.fieldMappers(fieldMappers);
        }
    }

    public void addFieldMapperListener(FieldMapperListener fieldMapperListener) {
        fieldMapperListeners.add(fieldMapperListener);
    }

    public void traverse(FieldMapperListener listener) {
        for (RootMapper rootMapper : mapping.rootMappers) {
            if (!rootMapper.includeInObject() && rootMapper instanceof FieldMapper) {
                listener.fieldMapper((FieldMapper) rootMapper);
            }
        }
        mapping.root.traverse(listener);
    }

    public void addObjectMappers(Collection<ObjectMapper> objectMappers) {
        addObjectMappers(objectMappers.toArray(new ObjectMapper[objectMappers.size()]));
    }

    private void addObjectMappers(ObjectMapper... objectMappers) {
        synchronized (mappersMutex) {
            MapBuilder<String, ObjectMapper> builder = MapBuilder.newMapBuilder(this.objectMappers);
            for (ObjectMapper objectMapper : objectMappers) {
                builder.put(objectMapper.fullPath(), objectMapper);
                if (objectMapper.nested().isNested()) {
                    hasNestedObjects = true;
                }
            }
            this.objectMappers = builder.immutableMap();
        }
        for (ObjectMapperListener objectMapperListener : objectMapperListeners) {
            objectMapperListener.objectMappers(objectMappers);
        }
    }

    public void addObjectMapperListener(ObjectMapperListener objectMapperListener) {
        objectMapperListeners.add(objectMapperListener);
    }

    public void traverse(ObjectMapperListener listener) {
        mapping.root.traverse(listener);
    }

    private MergeResult newMergeContext(boolean simulate) {
        return new MergeResult(simulate) {

            List<String> conflicts = new ArrayList<>();

            @Override
            public void addFieldMappers(Collection<FieldMapper<?>> fieldMappers) {
                assert simulate() == false;
                DocumentMapper.this.addFieldMappers(fieldMappers);
            }

            @Override
            public void addObjectMappers(Collection<ObjectMapper> objectMappers) {
                assert simulate() == false;
                DocumentMapper.this.addObjectMappers(objectMappers);
            }

            @Override
            public void addConflict(String mergeFailure) {
                conflicts.add(mergeFailure);
            }

            @Override
            public boolean hasConflicts() {
                return conflicts.isEmpty() == false;
            }

            @Override
            public String[] buildConflicts() {
                return conflicts.toArray(Strings.EMPTY_ARRAY);
            }

        };
    }

    public synchronized MergeResult merge(Mapping mapping, boolean simulate) {
        final MergeResult mergeResult = newMergeContext(simulate);
        this.mapping.merge(mapping, mergeResult);
        if (simulate == false) {
            refreshSource();
        }
        return mergeResult;
    }

    public CompressedString refreshSource() throws ElasticsearchGenerationException {
        try {
            BytesStreamOutput bStream = new BytesStreamOutput();
            XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON, CompressorFactory.defaultCompressor().streamOutput(bStream));
            builder.startObject();
            toXContent(builder, ToXContent.EMPTY_PARAMS);
            builder.endObject();
            builder.close();
            return mappingSource = new CompressedString(bStream.bytes());
        } catch (Exception e) {
            throw new ElasticsearchGenerationException("failed to serialize source for type [" + type + "]", e);
        }
    }

    public void close() {
        documentParser.close();
        mapping.root.close();
        for (RootMapper rootMapper : mapping.rootMappers) {
            rootMapper.close();
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return mapping.toXContent(builder, params);
    }

    /**
     * Script based source transformation.
     */
    private static class ScriptTransform implements SourceTransform {
        private final ScriptService scriptService;
        /**
         * Contents of the script to transform the source document before indexing.
         */
        private final String script;
        /**
         * The type of the script to run.
         */
        private final ScriptType scriptType;
        /**
         * Language of the script to transform the source document before indexing.
         */
        private final String language;
        /**
         * Parameters passed to the transform script.
         */
        private final Map<String, Object> parameters;

        public ScriptTransform(ScriptService scriptService, String script, ScriptType scriptType, String language, Map<String, Object> parameters) {
            this.scriptService = scriptService;
            this.script = script;
            this.scriptType = scriptType;
            this.language = language;
            this.parameters = parameters;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> transformSourceAsMap(Map<String, Object> sourceAsMap) {
            try {
                // We use the ctx variable and the _source name to be consistent with the update api.
                ExecutableScript executable = scriptService.executable(new Script(language, script, scriptType, parameters), ScriptContext.Standard.MAPPING);
                Map<String, Object> ctx = new HashMap<>(1);
                ctx.put("_source", sourceAsMap);
                executable.setNextVar("ctx", ctx);
                executable.run();
                ctx = (Map<String, Object>) executable.unwrap(ctx);
                return (Map<String, Object>) ctx.get("_source");
            } catch (Exception e) {
                throw new IllegalArgumentException("failed to execute script", e);
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("script", script);
            builder.field("lang", language);
            if (parameters != null) {
                builder.field("params", parameters);
            }
            builder.endObject();
            return builder;
        }
    }
}
