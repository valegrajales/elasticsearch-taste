package org.codelibs.elasticsearch.taste.rest.handler;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.elasticsearch.taste.TasteConstants;
import org.codelibs.elasticsearch.taste.exception.OperationFailedException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.joda.time.format.ISODateTimeFormat;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent.Params;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.indices.IndexAlreadyExistsException;

public class PreferenceRequestHandler extends DefaultRequestHandler {
    public PreferenceRequestHandler(final Settings settings, final Client client) {
        super(settings, client);
    }

    public boolean hasPreference(final Map<String, Object> requestMap) {
        return requestMap.containsKey("value");
    }

    @Override
    public void execute(final Params params,
            final RequestHandler.OnErrorListener listener,
            final Map<String, Object> requestMap,
            final Map<String, Object> paramMap, final RequestHandlerChain chain) {
        final String index = params.param("preference_index",
                params.param("index"));
        final String type = params.param("preference_type",
                params.param("type", TasteConstants.PREFERENCE_TYPE));
        final String userIdField = params.param(FIELD_USER_ID,
                TasteConstants.USER_ID_FIELD);
        final String itemIdField = params.param(FIELD_ITEM_ID,
                TasteConstants.ITEM_ID_FIELD);
        final String valueField = params.param(FIELD_VALUE,
                TasteConstants.VALUE_FIELD);
        final String timestampField = params.param(FIELD_TIMESTAMP,
                TasteConstants.TIMESTAMP_FIELD);

        final Number value = (Number) requestMap.get("value");
        if (value == null) {
            throw new InvalidParameterException("value is null.");
        }

        Date timestamp;
        final Object timestampObj = requestMap.get("timestamp");
        if (timestampObj == null) {
            timestamp = new Date();
        } else if (timestampObj instanceof String) {
            timestamp = new Date(ISODateTimeFormat.dateTime().parseMillis(
                    timestampObj.toString()));
        } else if (timestampObj instanceof Date) {
            timestamp = (Date) timestampObj;
        } else if (timestampObj instanceof Number) {
            timestamp = new Date(((Number) timestampObj).longValue());
        } else {
            throw new InvalidParameterException("timestamp is invalid format: "
                    + timestampObj);
        }

        final Long userId = (Long) paramMap.get(userIdField);
        final Long itemId = (Long) paramMap.get(itemIdField);

        final Map<String, Object> rootObj = new HashMap<>();
        rootObj.put(userIdField, userId);
        rootObj.put(itemIdField, itemId);
        rootObj.put(valueField, value);
        rootObj.put(timestampField, timestamp);
        client.prepareIndex(index, type).setSource(rootObj)
                .execute(new ActionListener<IndexResponse>() {
                    @Override
                    public void onResponse(final IndexResponse response) {
                        chain.execute(params, listener, requestMap, paramMap);
                    }

                    @Override
                    public void onFailure(final Throwable t) {
                        @SuppressWarnings("unchecked")
                        List<Throwable> errorList = (List<Throwable>) paramMap
                                .get(ERROR_LIST);
                        if (errorList == null) {
                            errorList = new ArrayList<>();
                            paramMap.put(ERROR_LIST, errorList);
                        }
                        if (errorList.size() >= maxRetryCount) {
                            listener.onError(t);
                        } else {
                            errorList.add(t);
                            doPreferenceIndexCreation(params, listener,
                                    requestMap, paramMap, chain);
                        }
                    }
                });
    }

    private void doPreferenceIndexCreation(final Params params,
            final RequestHandler.OnErrorListener listener,
            final Map<String, Object> requestMap,
            final Map<String, Object> paramMap, final RequestHandlerChain chain) {
        final String index = params.param("index");

        client.admin().indices().prepareExists(index)
                .execute(new ActionListener<IndicesExistsResponse>() {

                    @Override
                    public void onResponse(
                            final IndicesExistsResponse indicesExistsResponse) {
                        if (indicesExistsResponse.isExists()) {
                            doPreferenceMappingCreation(params, listener,
                                    requestMap, paramMap, chain);
                        } else {
                            client.admin()
                                    .indices()
                                    .prepareCreate(index)
                                    .execute(
                                            new ActionListener<CreateIndexResponse>() {

                                                @Override
                                                public void onResponse(
                                                        final CreateIndexResponse createIndexResponse) {
                                                    if (createIndexResponse
                                                            .isAcknowledged()) {
                                                        doPreferenceMappingCreation(
                                                                params,
                                                                listener,
                                                                requestMap,
                                                                paramMap, chain);
                                                    } else {
                                                        onFailure(new OperationFailedException(
                                                                "Failed to create "
                                                                        + index));
                                                    }
                                                }

                                                @Override
                                                public void onFailure(
                                                        final Throwable t) {
                                                    if (t instanceof IndexAlreadyExistsException) {
                                                        doPreferenceIndexCreation(
                                                                params,
                                                                listener,
                                                                requestMap,
                                                                paramMap, chain);
                                                    } else {
                                                        listener.onError(t);
                                                    }
                                                }
                                            });
                        }
                    }

                    @Override
                    public void onFailure(final Throwable t) {
                        listener.onError(t);
                    }
                });
    }

    private void doPreferenceMappingCreation(final Params params,
            final RequestHandler.OnErrorListener listener,
            final Map<String, Object> requestMap,
            final Map<String, Object> paramMap, final RequestHandlerChain chain) {
        final String index = params.param("index");
        final String type = params
                .param("type", TasteConstants.PREFERENCE_TYPE);
        final String userIdField = params.param(FIELD_USER_ID,
                TasteConstants.USER_ID_FIELD);
        final String itemIdField = params.param(FIELD_ITEM_ID,
                TasteConstants.ITEM_ID_FIELD);
        final String valueField = params.param(FIELD_VALUE,
                TasteConstants.VALUE_FIELD);
        final String timestampField = params.param(FIELD_TIMESTAMP,
                TasteConstants.TIMESTAMP_FIELD);

        try {
            final XContentBuilder builder = XContentFactory.jsonBuilder()//
                    .startObject()//
                    .startObject(type)//
                    .startObject("properties")//

                    // @timestamp
                    .startObject(timestampField)//
                    .field("type", "date")//
                    .field("format", "dateOptionalTime")//
                    .endObject()//

                    // user_id
                    .startObject(userIdField)//
                    .field("type", "long")//
                    .endObject()//

                    // item_id
                    .startObject(itemIdField)//
                    .field("type", "long")//
                    .endObject()//

                    // value
                    .startObject(valueField)//
                    .field("type", "double")//
                    .endObject()//

                    .endObject()//
                    .endObject()//
                    .endObject();

            client.admin().indices().preparePutMapping(index).setType(type)
                    .setSource(builder)
                    .execute(new ActionListener<PutMappingResponse>() {

                        @Override
                        public void onResponse(
                                final PutMappingResponse queueMappingResponse) {
                            if (queueMappingResponse.isAcknowledged()) {
                                execute(params, listener, requestMap, paramMap,
                                        chain);
                            } else {
                                onFailure(new OperationFailedException(
                                        "Failed to create mapping for " + index
                                                + "/" + type));
                            }
                        }

                        @Override
                        public void onFailure(final Throwable t) {
                            listener.onError(t);
                        }
                    });
        } catch (final Exception e) {
            listener.onError(e);
        }
    }

}