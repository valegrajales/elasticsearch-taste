package org.codelibs.elasticsearch.taste.rest;

import static org.codelibs.elasticsearch.util.action.ListenerUtils.on;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.codelibs.elasticsearch.taste.TasteConstants;
import org.codelibs.elasticsearch.taste.exception.NotFoundException;
import org.codelibs.elasticsearch.taste.exception.OperationFailedException;
import org.codelibs.elasticsearch.taste.exception.TasteException;
import org.codelibs.elasticsearch.taste.river.handler.ItemsFromUserRestActionHandler;
import org.codelibs.elasticsearch.util.action.ListenerUtils.OnResponseListener;
import org.codelibs.elasticsearch.util.lang.StringUtils;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.CacheBuilder;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.river.RiverSettings;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.sort.SortOrder;
import static org.elasticsearch.common.xcontent.XContentFactory.*;

public class TasteSearchRestAction extends BaseRestHandler {

	private Cache<String, Map<String, Object>> cache;

	private static final long timeoutMillis = 10 * 1000;

	@Inject
	public TasteSearchRestAction(final Settings settings, final Client client,
			final RestController restController) {
		super(settings, restController, client);

		final String size = settings.get("taste.cache.search.size", "1000");
		final String duration = settings.get("taste.cache.search.duration",
				"600000"); // 10min
		cache = CacheBuilder
				.newBuilder()
				.expireAfterAccess(Long.parseLong(duration),
						TimeUnit.MILLISECONDS)
						.maximumSize(Long.parseLong(size)).build();

		restController.registerHandler(RestRequest.Method.GET,
				"/{index}/_taste/{objectType}/{systemId}", this);
		restController.registerHandler(RestRequest.Method.GET,
				"/{index}/{type}/_taste/{objectType}/{systemId}", this);
		restController.registerHandler(RestRequest.Method.GET,"/{index}/_taste/recommender/{action}/{objectType}/{systemId}", this);

	}

	@Override
	protected void handleRequest(final RestRequest request,
			final RestChannel channel, final Client client) throws IOException {

		final Info info = new Info(request);

		final String systemId = request.param("systemId");
		if (StringUtils.isBlank(systemId)) {
			onError(channel, new NotFoundException("No system_id."));
			return;
		}
		String[] systemIds = systemId.trim().split(",");
		if(systemIds.length == 0) {
			onError(channel, new NotFoundException("No system_id."));
			return;
		}

		if (info.getIdIndex() == null) {
			onError(channel, new NotFoundException("No search type."));
			return;
		}

		final String recommendType = request.param("action");
		if(!StringUtils.isBlank(recommendType)) {
			if("recommend_a".equals(recommendType)) {
				// Create parser from source content
		    BytesReference source = request.content();
		    XContentType xContentType = org.elasticsearch.common.xcontent.XContentFactory.xContentType(source);
		    XContent xContent = org.elasticsearch.common.xcontent.XContentFactory.xContent(xContentType);
		    XContentParser parser = xContent.createParser(source);
		    XContentParser.Token t = parser.nextToken();
		    if (t == null) {
		        throw new IllegalArgumentException("Unable to parse input data");
		    }
				final ItemsFromUserRestActionHandler handler = new ItemsFromUserRestActionHandler(settings, client);
				handler.execute();
			}
		} else {
			final OnResponseListener<SearchResponse> responseListener = searchResponse -> {
				final SearchHits hits = searchResponse.getHits();
				if (hits.totalHits() == 0) {
					onError(channel,
							new NotFoundException("No " + info.getIdField()
									+ " data for " + systemId + " in "
									+ info.getIdIndex() + "/" + info.getIdType()));
					return;
				}

				SearchHit[] searchHits = hits.getHits();
				long[] targetIds = new long[hits.getHits().length];
				for(int i=0;i<targetIds.length && i<searchHits.length; i++) {
					SearchHit hit = searchHits[i];
					final SearchHitField field = hit.field(info.getIdField());
					Number targetId = field.getValue();
					if(targetId != null) {
						targetIds[i] = targetId.longValue();
					}
				}

				if (targetIds.length == 0) {
					onError(channel,
							new NotFoundException("No " + info.getIdField()
									+ " for " + systemId + " in "
									+ info.getIdIndex() + "/" + info.getIdType()));
					return;
				}


				doSearchRequest(request, channel, client, info,
						targetIds);
			};

			BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
			for(String id: systemIds) {
				boolQueryBuilder.should(QueryBuilders.termQuery("system_id", id));
			}
			boolQueryBuilder.minimumNumberShouldMatch(1);

			client.prepareSearch(info.getIdIndex()).setTypes(info.getIdType())
			.setQuery(boolQueryBuilder)
			.addField(info.getIdField())
			.addSort(info.getTimestampField(), SortOrder.DESC)
			.setSize(systemIds.length)
			.execute(on(responseListener, t -> onError(channel, t)));

		}


	}

	private void doSearchRequest(final RestRequest request,
			final RestChannel channel, final Client client, final Info info,
			final long[] targetIds) {
		final Map<Long, SearchResponse> responseMap = new ConcurrentHashMap<>();
		final CountDownLatch latch = new CountDownLatch(targetIds.length);
		final List<Throwable> exceptionList = Collections.synchronizedList(new ArrayList<>());
		final String targetIdField = info.getTargetIdField();
		final TimeValue timeout = TimeValue.timeValueMillis(timeoutMillis);
		for (final long id : targetIds) {
			client.prepareSearch(info.getTargetIndex())
			.setTypes(info.getTargetType())
			.setQuery(QueryBuilders.termQuery(targetIdField, id))
			.addSort(info.getTimestampField(), SortOrder.DESC)
			.setSize(info.getSize()).setFrom(info.getFrom())
			.setTimeout(timeout)
			.execute(on(
					response -> {
						responseMap.put(id, response);
						latch.countDown();
					},
					t -> {
						exceptionList.add(t);
						latch.countDown();
					}));
		}

		boolean isTimeOut = false;
		try {
			if ( !latch.await(timeoutMillis, TimeUnit.MILLISECONDS) ) {
				isTimeOut = true;
			}
		} catch (InterruptedException e) {
			//ignore
		}

		long tookInMillis = -1;
		long totalHits = 0;
		float maxScore = 0F;
		List<SearchHit> searchHitList = new ArrayList<>();
		for (long targetId : targetIds) {
			SearchResponse response = responseMap.get(targetId);
			if (response != null) {
				if (response.getTookInMillis() > tookInMillis) {
					tookInMillis = response.getTookInMillis();
				}
				if (response.isTimedOut()) {
					isTimeOut = true;
				}

				final SearchHits hits = response.getHits();
				totalHits += hits.getTotalHits();
				if (hits.getMaxScore() > maxScore) {
					maxScore = hits.getMaxScore();
				}
				searchHitList.addAll(Arrays.asList(hits.getHits()));
			}
		}

		if (searchHitList.size() == 0 && !isTimeOut) {
			StringBuilder message = new StringBuilder();
			message.append("No ID for [");
					for (int i = 0; i < targetIds.length; i++) {
						if (i > 0) {
							message.append(',');
						}
						message.append(targetIds[i]);
					}
					message.append("] in ")
					.append(info.getTargetIndex())
					.append("/")
					.append(info.getTargetType());
					if (exceptionList.size() > 0) {
						message.append(" Errors:[");
						for (int i = 0; i < exceptionList.size(); i++) {
							if (i > 0) {
								message.append(", ");
							}
							message.append("{")
							.append(exceptionList.get(i))
							.append("}");
						}
						message.append("]");
					}
					onError(channel,
							new NotFoundException(message.toString()));
					return;
		}

		try {
			final XContentBuilder builder = jsonBuilder();
			final String pretty = request.param("pretty");
			if (pretty != null && !"false".equalsIgnoreCase(pretty)) {
				builder.prettyPrint().lfAtEnd();
			}
			builder.startObject()//
			.field("took", tookInMillis)//
			.field("timed_out", isTimeOut);

			if (exceptionList.size() > 0) {
				builder.startObject("errors")
				.field("num", exceptionList.size())
				.startArray("messages");
				for (Throwable t : exceptionList) {
					builder.value(t.getMessage());
				}
				builder.endArray()
				.endObject();
			}

			builder.startObject("hits")//
			.field("total", totalHits)//
			.field("max_score", maxScore)//
			.startArray("hits");
			for (final SearchHit hit : searchHitList) {
				final Map<String, Object> source = expandObjects(client,
						hit.getSource(), info);
				builder.startObject()//
				.field("_index", hit.getIndex())//
				.field("_type", hit.getType())//
				.field("_id", hit.getId())//
				.field("_score", hit.getScore())//
				.field("_source", source)//
				.endObject();//
			}

			builder.endArray()//
			.endObject()//
			.endObject();

			channel.sendResponse(new BytesRestResponse(RestStatus.OK,
					builder));
		} catch (final IOException e) {
			throw new OperationFailedException(
					"Failed to build a response.", e);
		}
	}

	private Map<String, Object> expandObjects(final Client client,
			final Map<String, Object> source, final Info info) {
		final Map<String, Object> newSource = new HashMap<>(source.size());
		for (final Map.Entry<String, Object> entry : source.entrySet()) {
			final Object value = entry.getValue();
			if (info.getUserIdField().equals(entry.getKey()) && value != null) {
				final Number targetId = (Number) value;
				final Map<String, Object> objMap = getObjectMap(client, "U-",
						info.getUserIndex(), info.getUserType(),
						targetId.toString());
				newSource.put("user", objMap);
			} else if (info.getItemIdField().equals(entry.getKey())
					&& value != null) {
				final Number targetId = (Number) value;
				final Map<String, Object> objMap = getObjectMap(client, "I-",
						info.getItemIndex(), info.getItemType(),
						targetId.toString());
				newSource.put("item", objMap);
			} else if (value instanceof Map) {
				@SuppressWarnings("unchecked")
				final Map<String, Object> objMap = (Map<String, Object>) value;
				newSource.put(entry.getKey(),
						expandObjects(client, objMap, info));
			} else if (value instanceof List) {
				@SuppressWarnings("unchecked")
				final List<Object> list = (List<Object>) value;
				final List<Object> newList = new ArrayList<>(list.size());
				for (final Object obj : list) {
					if (obj instanceof Map) {
						@SuppressWarnings("unchecked")
						final Map<String, Object> objMap = (Map<String, Object>) obj;
						newList.add(expandObjects(client, objMap, info));
					} else {
						newList.add(obj);
					}
				}
				newSource.put(entry.getKey(), newList);
			} else {
				newSource.put(entry.getKey(), value);
			}
		}
		return newSource;
	}

	private Map<String, Object> getObjectMap(final Client client,
			final String prefix, final String index, final String type,
			final String id) {
		try {
			return cache.get(prefix + id, () -> {
				final GetResponse response = client.prepareGet(index, type, id)
						.execute().actionGet();
				if (response.isExists()) {
					return response.getSource();
				}
				return null;
			});
		} catch (final ExecutionException e) {
			throw new TasteException("Failed to get data for " + index + "/"
					+ type + "/" + id, e);
		}
	}

	private void onError(final RestChannel channel, final Throwable t) {
		try {
			channel.sendResponse(new BytesRestResponse(channel, t));
		} catch (final Exception e) {
			logger.error("Failed to send a failure response.", e);
		}
	}

	private static class Info {
		private static final String ITEM = "item";

		private static final String USER = "user";

		private String targetIndex;

		private String targetType;

		private String targetIdField;

		private String userIndex;

		private String userType;

		private String userIdField;

		private String itemIndex;

		private String itemType;

		private String itemIdField;

		private String idIndex;

		private String idType;

		private String idField;

		private String timestampField;

		private String objectType;

		private int size;

		private int from;

		Info(final RestRequest request) {
			size = request.paramAsInt("size", 10);
			from = request.paramAsInt("from", 0);
			targetIndex = request.param("index");
			targetType = request.param("type");
			userIndex = request.param(TasteConstants.REQUEST_PARAM_USER_INDEX,
					targetIndex);
			userType = request.param(TasteConstants.REQUEST_PARAM_USER_TYPE,
					TasteConstants.USER_TYPE);
			itemIndex = request.param(TasteConstants.REQUEST_PARAM_ITEM_INDEX,
					targetIndex);
			itemType = request.param(TasteConstants.REQUEST_PARAM_ITEM_TYPE,
					TasteConstants.ITEM_TYPE);
			userIdField = request.param(
					TasteConstants.REQUEST_PARAM_USER_ID_FIELD,
					TasteConstants.USER_ID_FIELD);
			itemIdField = request.param(
					TasteConstants.REQUEST_PARAM_ITEM_ID_FIELD,
					TasteConstants.ITEM_ID_FIELD);
			timestampField = request.param(
					TasteConstants.REQUEST_PARAM_TIMESTAMP_FIELD,
					TasteConstants.TIMESTAMP_FIELD);
			objectType = request.param("objectType");
			if (USER.equals(objectType)) {
				idIndex = userIndex;
				idType = userType;
				idField = request.param(TasteConstants.REQUEST_PARAM_ID_FIELD,
						userIdField);
			} else if (ITEM.equals(objectType)) {
				idIndex = itemIndex;
				idType = itemType;
				idField = request.param(TasteConstants.REQUEST_PARAM_ID_FIELD,
						itemIdField);
			}
			targetIdField = request.param(
					TasteConstants.REQUEST_PARAM_TARGET_ID_FIELD, idField);
		}

		public int getFrom() {
			return from;
		}

		public int getSize() {
			return size;
		}

		public String getTargetIndex() {
			return targetIndex;
		}

		public String getTargetType() {
			return targetType;
		}

		public String getTargetIdField() {
			return targetIdField;
		}

		public String getUserIndex() {
			return userIndex;
		}

		public String getUserType() {
			return userType;
		}

		public String getItemIndex() {
			return itemIndex;
		}

		public String getItemType() {
			return itemType;
		}

		public String getUserIdField() {
			return userIdField;
		}

		public String getItemIdField() {
			return itemIdField;
		}

		public String getTimestampField() {
			return timestampField;
		}

		public String getIdIndex() {
			return idIndex;
		}

		public String getIdType() {
			return idType;
		}

		public String getIdField() {
			return idField;
		}

	}
}
