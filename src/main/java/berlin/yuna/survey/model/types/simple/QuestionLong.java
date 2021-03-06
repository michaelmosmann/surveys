package berlin.yuna.survey.model.types.simple;

import berlin.yuna.survey.model.types.QuestionGeneric;

import java.util.Optional;

public class QuestionLong extends QuestionGeneric<Long, QuestionLong> {


    @Override
    public Optional<Long> parse(final Object answer) {
        if (answer instanceof Number) {
            return Optional.of(((Number) answer).longValue());
        }
        try {
            return Optional.of(Long.valueOf(String.valueOf(answer)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static QuestionLong of(final Enum<?> label) {
        return label == null ? null : of(label.name());
    }

    public static QuestionLong of(final String label) {
        return getOrNew(label, QuestionLong.class, () -> new QuestionLong(label));
    }

    private QuestionLong(String label) {
        super(label);
    }
}
