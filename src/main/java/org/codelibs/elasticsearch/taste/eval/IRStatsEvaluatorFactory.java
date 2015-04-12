package org.codelibs.elasticsearch.taste.eval;

import java.util.Map;

import org.codelibs.elasticsearch.util.settings.SettingsUtils;

public class IRStatsEvaluatorFactory implements IRRecommenderEvaluatorFactory {
    protected Number maxPreference;

    protected Number minPreference;

    @Override
    public void init(final Map<String, Object> settings) {
        maxPreference = SettingsUtils.get(settings, "max_preference");
        minPreference = SettingsUtils.get(settings, "min_preference");
    }

    @Override
    public RecommenderIRStatsEvaluator create() {
        final RecommenderIRStatsEvaluator evaluator = new GenericRecommenderIRStatsEvaluator();
        return evaluator;
    }

}
