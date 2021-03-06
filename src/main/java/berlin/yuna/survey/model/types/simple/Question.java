package berlin.yuna.survey.model.types.simple;

import berlin.yuna.survey.model.types.QuestionGeneric;

import java.util.Optional;

public class Question extends QuestionGeneric<String, Question> {


    @Override
    public Optional<String> parse(final Object answer) {
        return Optional.of(String.valueOf(answer));
    }

    public static Question of(final Enum<?> label) {
        return label == null ? null : of(label.name());
    }

    public static Question of(final String label) {
        return getOrNew(label, Question.class, () -> new Question(label));
    }

    private Question(String label) {
        super(label);
    }
}
