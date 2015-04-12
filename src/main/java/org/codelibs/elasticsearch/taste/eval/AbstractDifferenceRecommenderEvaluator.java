/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codelibs.elasticsearch.taste.eval;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.mahout.common.RandomUtils;
import org.codelibs.elasticsearch.taste.common.FastByIDMap;
import org.codelibs.elasticsearch.taste.common.FullRunningAverageAndStdDev;
import org.codelibs.elasticsearch.taste.common.LongPrimitiveIterator;
import org.codelibs.elasticsearch.taste.common.RunningAverageAndStdDev;
import org.codelibs.elasticsearch.taste.eval.AbstractDifferenceEvaluator.EstimateStatsResult;
import org.codelibs.elasticsearch.taste.eval.AbstractDifferenceEvaluator.PreferenceEstimateCallable;
import org.codelibs.elasticsearch.taste.exception.NoSuchItemException;
import org.codelibs.elasticsearch.taste.exception.NoSuchUserException;
import org.codelibs.elasticsearch.taste.exception.TasteException;
import org.codelibs.elasticsearch.taste.model.DataModel;
import org.codelibs.elasticsearch.taste.model.GenericDataModel;
import org.codelibs.elasticsearch.taste.model.GenericPreference;
import org.codelibs.elasticsearch.taste.model.GenericUserPreferenceArray;
import org.codelibs.elasticsearch.taste.model.Preference;
import org.codelibs.elasticsearch.taste.model.PreferenceArray;
import org.codelibs.elasticsearch.taste.recommender.Recommender;
import org.codelibs.elasticsearch.taste.writer.ResultWriter;
import org.codelibs.elasticsearch.util.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Abstract superclass of a couple implementations, providing shared functionality.
 */
public abstract class AbstractDifferenceRecommenderEvaluator implements
RecommenderEvaluator {

	private static final Logger log = LoggerFactory
			.getLogger(AbstractDifferenceRecommenderEvaluator.class);

	protected ResultWriter resultWriter;

	protected String id;
	
	private boolean interrupted = false;

	private final Random random;

	protected AbstractDifferenceRecommenderEvaluator() {
		random = RandomUtils.getRandom();
	}

	@Override
	public void setResultWriter(final ResultWriter resultWriter) {
		this.resultWriter = resultWriter;
	}

	@Override
	public void setId(final String id) {
		this.id = id;
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public Evaluation evaluate(final RecommenderBuilder recommenderBuilder,
			final DataModel dataModel, final EvaluationConfig config) {
		Preconditions.checkNotNull(recommenderBuilder);
		Preconditions.checkNotNull(dataModel);
		final double trainingPercentage = config.getTrainingPercentage();
		final double evaluationPercentage = config.getEvaluationPercentage();
		Preconditions.checkArgument(trainingPercentage >= 0.0
				&& trainingPercentage <= 1.0, "Invalid trainingPercentage: "
						+ trainingPercentage
						+ ". Must be: 0.0 <= trainingPercentage <= 1.0");
		Preconditions.checkArgument(evaluationPercentage >= 0.0
				&& evaluationPercentage <= 1.0,
				"Invalid evaluationPercentage: " + evaluationPercentage
				+ ". Must be: 0.0 <= evaluationPercentage <= 1.0");

		log.info("Beginning evaluation using {} of {}", trainingPercentage,
				dataModel);

		final int numUsers = dataModel.getNumUsers();
		final FastByIDMap<PreferenceArray> trainingPrefs = new FastByIDMap<PreferenceArray>(
				1 + (int) (evaluationPercentage * numUsers));
		final FastByIDMap<PreferenceArray> testPrefs = new FastByIDMap<PreferenceArray>(
				1 + (int) (evaluationPercentage * numUsers));

		final LongPrimitiveIterator it = dataModel.getUserIDs();
		while (it.hasNext()) {
			final long userID = it.nextLong();
			if (random.nextDouble() < evaluationPercentage) {
				splitOneUsersPrefs(trainingPercentage, trainingPrefs,
						testPrefs, userID, dataModel);
			}
		}

		final DataModel trainingModel = new GenericDataModel(trainingPrefs);

		final Recommender recommender = recommenderBuilder
				.buildRecommender(trainingModel);

		final Evaluation result = getEvaluation(testPrefs, recommender,
				config.getMarginForError());
		result.setTraining(trainingPrefs.size());
		result.setTest(testPrefs.size());
		log.info("Evaluation result: {}", result);

		if (resultWriter != null) {
			IOUtils.closeQuietly(resultWriter);
		}

		return result;
	}
	
	@Override
  public void interrupt() {
		interrupted = true;
  }

	/*@Override
    public double evaluate(final RecommenderBuilder recommenderBuilder,
            final DataModelBuilder dataModelBuilder, final DataModel dataModel,
            final double trainingPercentage, final double evaluationPercentage) {
        Preconditions.checkNotNull(recommenderBuilder);
        Preconditions.checkNotNull(dataModel);
        Preconditions.checkArgument(trainingPercentage >= 0.0
                && trainingPercentage <= 1.0, "Invalid trainingPercentage: "
                + trainingPercentage
                + ". Must be: 0.0 <= trainingPercentage <= 1.0");
        Preconditions.checkArgument(evaluationPercentage >= 0.0
                && evaluationPercentage <= 1.0,
                "Invalid evaluationPercentage: " + evaluationPercentage
                        + ". Must be: 0.0 <= evaluationPercentage <= 1.0");

        log.info("Beginning evaluation using {} of {}", trainingPercentage,
                dataModel);

        final int numUsers = dataModel.getNumUsers();
        final FastByIDMap<PreferenceArray> trainingPrefs = new FastByIDMap<PreferenceArray>(
                1 + (int) (evaluationPercentage * numUsers));
        final FastByIDMap<PreferenceArray> testPrefs = new FastByIDMap<PreferenceArray>(
                1 + (int) (evaluationPercentage * numUsers));

        final LongPrimitiveIterator it = dataModel.getUserIDs();
        while (it.hasNext()) {
            final long userID = it.nextLong();
            if (random.nextDouble() < evaluationPercentage) {
                splitOneUsersPrefs(trainingPercentage, trainingPrefs,
                        testPrefs, userID, dataModel);
            }
        }

        final DataModel trainingModel = dataModelBuilder == null ? new GenericDataModel(
                trainingPrefs) : dataModelBuilder.buildDataModel(trainingPrefs);

        final Recommender recommender = recommenderBuilder
                .buildRecommender(trainingModel);

        final double result = getEvaluation(testPrefs, recommender);
        log.info("Evaluation result: {}", result);
        return result;
    }*/

	private void splitOneUsersPrefs(final double trainingPercentage,
			final FastByIDMap<PreferenceArray> trainingPrefs,
			final FastByIDMap<PreferenceArray> testPrefs, final long userID,
			final DataModel dataModel) {
		List<Preference> oneUserTrainingPrefs = null;
		List<Preference> oneUserTestPrefs = null;
		final PreferenceArray prefs = dataModel.getPreferencesFromUser(userID);
		final int size = prefs.length();
		for (int i = 0; i < size; i++) {
			final Preference newPref = new GenericPreference(userID,
					prefs.getItemID(i), prefs.getValue(i));
			if (random.nextDouble() < trainingPercentage) {
				if (oneUserTrainingPrefs == null) {
					oneUserTrainingPrefs = Lists.newArrayListWithCapacity(3);
				}
				oneUserTrainingPrefs.add(newPref);
			} else {
				if (oneUserTestPrefs == null) {
					oneUserTestPrefs = Lists.newArrayListWithCapacity(3);
				}
				oneUserTestPrefs.add(newPref);
			}
		}
		if (oneUserTrainingPrefs != null) {
			trainingPrefs.put(userID, new GenericUserPreferenceArray(
					oneUserTrainingPrefs));
			if (oneUserTestPrefs != null) {
				testPrefs.put(userID, new GenericUserPreferenceArray(
						oneUserTestPrefs));
			}
		}
	}

	protected Evaluation getEvaluation(
			final FastByIDMap<PreferenceArray> testPrefs,
			final Recommender recommender, final float marginForError) {
		reset();
    final Collection<Callable<Void>> estimateCallables = Lists
            .newArrayList();
    final AtomicInteger noEstimateCounter = new AtomicInteger();
    for (final Map.Entry<Long, PreferenceArray> entry : testPrefs
            .entrySet()) {
        estimateCallables.add(new PreferenceEstimateCallable(recommender,
                entry.getKey(), entry.getValue(), noEstimateCounter));
    }
    log.info("Beginning evaluation of {} users", estimateCallables.size());
    final RunningAverageAndStdDev timing = new FullRunningAverageAndStdDev();
    execute(estimateCallables, noEstimateCounter, timing);

		final Evaluation evaluation = new Evaluation();
		evaluation.setScore(computeFinalEvaluation());
		evaluation.setTotalProcessingTime(timing.getCount());
		evaluation.setStdDeviation(timing.getStandardDeviation());
		return evaluation;
	}


	/*private double getEvaluation(final FastByIDMap<PreferenceArray> testPrefs,
            final Recommender recommender) {
        reset();
        final Collection<Callable<Void>> estimateCallables = Lists
                .newArrayList();
        final AtomicInteger noEstimateCounter = new AtomicInteger();
        for (final Map.Entry<Long, PreferenceArray> entry : testPrefs
                .entrySet()) {
            estimateCallables.add(new PreferenceEstimateCallable(recommender,
                    entry.getKey(), entry.getValue(), noEstimateCounter));
        }
        log.info("Beginning evaluation of {} users", estimateCallables.size());
        final RunningAverageAndStdDev timing = new FullRunningAverageAndStdDev();
        execute(estimateCallables, noEstimateCounter, timing);
        return computeFinalEvaluation();
    }*/

	protected static void execute(final Collection<Callable<Void>> callables,
			final AtomicInteger noEstimateCounter,
			final RunningAverageAndStdDev timing) {

		final Collection<Callable<Void>> wrappedCallables = wrapWithStatsCallables(
				callables, noEstimateCounter, timing);
		final int numProcessors = Runtime.getRuntime().availableProcessors();
		final ExecutorService executor = Executors
				.newFixedThreadPool(numProcessors);
		log.info("Starting timing of {} tasks in {} threads",
				wrappedCallables.size(), numProcessors);
		try {
			final List<Future<Void>> futures = executor
					.invokeAll(wrappedCallables);
			// Go look for exceptions here, really
			for (final Future<Void> future : futures) {
				future.get();
			}

		} catch (final InterruptedException ie) {
			throw new TasteException(ie);
		} catch (final ExecutionException ee) {
			throw new TasteException(ee.getCause());
		}

		executor.shutdown();
		try {
			executor.awaitTermination(10, TimeUnit.SECONDS);
		} catch (final InterruptedException e) {
			throw new TasteException(e.getCause());
		}
	}

	private static Collection<Callable<Void>> wrapWithStatsCallables(
			final Iterable<Callable<Void>> callables,
			final AtomicInteger noEstimateCounter,
			final RunningAverageAndStdDev timing) {
		final Collection<Callable<Void>> wrapped = Lists.newArrayList();
		int count = 0;
		for (final Callable<Void> callable : callables) {
			final boolean logStats = count++ % 1000 == 0; // log every 1000 or so iterations
			wrapped.add(new StatsCallable(callable, logStats, timing,
					noEstimateCounter));
		}
		return wrapped;
	}

	protected abstract void reset();

	protected abstract void processOneEstimate(float estimatedPreference,
			Preference realPref);

	protected abstract double computeFinalEvaluation();

	public final class PreferenceEstimateCallable implements Callable<Void> {

		private final Recommender recommender;

		private final long testUserID;

		private final PreferenceArray prefs;

		private final AtomicInteger noEstimateCounter;

		public PreferenceEstimateCallable(final Recommender recommender,
				final long testUserID, final PreferenceArray prefs,
				final AtomicInteger noEstimateCounter) {
			this.recommender = recommender;
			this.testUserID = testUserID;
			this.prefs = prefs;
			this.noEstimateCounter = noEstimateCounter;
		}

		@Override
		public Void call() {
			for (final Preference realPref : prefs) {
				float estimatedPreference = Float.NaN;
				try {
					estimatedPreference = recommender.estimatePreference(
							testUserID, realPref.getItemID());
				} catch (final NoSuchUserException nsue) {
					// It's possible that an item exists in the test data but not training data in which case
					// NSEE will be thrown. Just ignore it and move on.
					log.info(
							"User exists in test data but not training data: {}",
							testUserID);
				} catch (final NoSuchItemException nsie) {
					log.info(
							"Item exists in test data but not training data: {}",
							realPref.getItemID());
				}
				if (Float.isNaN(estimatedPreference)) {
					noEstimateCounter.incrementAndGet();
				} else {
					processOneEstimate(estimatedPreference, realPref);
				}
			}
			return null;
		}

	}

}
