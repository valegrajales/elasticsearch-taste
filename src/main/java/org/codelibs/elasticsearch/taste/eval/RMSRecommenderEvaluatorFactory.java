package org.codelibs.elasticsearch.taste.eval;

import java.util.Map;

import org.codelibs.elasticsearch.util.settings.SettingsUtils;

public class RMSRecommenderEvaluatorFactory implements RecommenderEvaluatorFactory {
    protected Number maxPreference;

    protected Number minPreference;

    @Override
    public void init(final Map<String, Object> settings) {
        maxPreference = SettingsUtils.get(settings, "max_preference");
        minPreference = SettingsUtils.get(settings, "min_preference");
    }

    @Override
    public RMSRecommenderEvaluator create() {
        final RMSRecommenderEvaluator evaluator = new RMSRecommenderEvaluator();
        return evaluator;
    }

}
