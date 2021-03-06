package berlin.yuna.survey.logic;

import berlin.yuna.survey.model.HistoryItem;
import berlin.yuna.survey.model.types.QuestionGeneric;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toList;

public class Survey {

    private QuestionGeneric<?, ?> last;
    //https://stackoverflow.com/questions/4724995/lock-free-concurrent-linked-list-in-java
    private final LinkedList<HistoryItem> history = new LinkedList<>();

    /**
     * Starts new SurveyCtx
     *
     * @param startQuestion {@link QuestionGeneric} to start with
     * @return {@link Survey}
     * @throws IllegalStateException on {@code null}
     */
    public static Survey init(final Enum<?> startQuestion) {
        if (startQuestion == null) {
            throw new IllegalArgumentException("Missing enum, given was null");
        }
        return new Survey(QuestionGeneric.get(startQuestion.name()));
    }

    /**
     * Starts new SurveyCtx
     *
     * @param startQuestion {@link QuestionGeneric} to start with
     * @return {@link Survey}
     * @throws IllegalStateException on {@code null} or when the {@link QuestionGeneric} was not found
     */
    public static Survey init(final String startQuestion) {
        return new Survey(QuestionGeneric.get(startQuestion));
    }

    /**
     * Starts new SurveyCtx
     *
     * @param startQuestion {@link QuestionGeneric} to start with
     * @return {@link Survey}
     * @throws IllegalStateException on {@code null}
     */
    public static Survey init(final QuestionGeneric<?, ?> startQuestion) {
        return new Survey(startQuestion);
    }

    /**
     * Continues {@link Survey} from a history
     * Removes all invalid {@link QuestionGeneric} items from the history
     *
     * @param history should not be empty as {@link Survey} needs a start item
     * @return {@link Survey}
     * @throws IllegalStateException when the {@code history} is empty or has no valid {@link QuestionGeneric}
     */
    public static Survey init(final Iterable<HistoryItem> history) {
        final LinkedList<HistoryItem> linkedHistory = StreamSupport.stream(history.spliterator(), false).filter(answer -> QuestionGeneric.exists(answer.getLabel())).collect(Collectors.toCollection(LinkedList::new));
        Survey context = init(linkedHistory.isEmpty() ? null : linkedHistory.getFirst().getLabel());
        context.history.clear();
        context.history.addAll(linkedHistory);
        context.last = findLast(linkedHistory);
        return context;
    }

    /**
     * Transit to a specific {@link QuestionGeneric} in the flow
     *
     * @param label for {@link QuestionGeneric} to transition to
     * @return {@code true} if transition is allowed, {@code false} on config of {@link QuestionGeneric#onBack(Object)}
     * @throws IllegalArgumentException if the label is not part of the flow or when the forward transition has not enough answers
     */
    public boolean transitTo(final String label) {
        return transitTo(QuestionGeneric.get(label));
    }

    /**
     * Transit to a specific {@link QuestionGeneric} in the flow
     *
     * @param target {@link QuestionGeneric} to transition to
     * @return {@code true} if transition is allowed, {@code false} on config of {@link QuestionGeneric#onBack(Object)}
     * @throws IllegalArgumentException if the label is not part of the flow or when the forward transition has not
     *                                  enough answers (will transition to the nearest possible {@link QuestionGeneric})
     */
    public boolean transitTo(final QuestionGeneric<?, ?> target) {
        if (target.equals(get())) {
            return true;
        }
        boolean result = true;
        assertExists(target);
        QuestionGeneric<?, ?> flowStart = getFirst();
        assertQuestionBelongsToFlow(target, flowStart);

        if (history.stream().filter(HistoryItem::isNotDraft).anyMatch(target::match)) {
            result = runBackTransitions(target);
        } else {
            final Map<String, Object> mappedHistory = getHistoryAnswered().collect(toLinkedMap(HistoryItem::getLabel, HistoryItem::getAnswer));
            String label = flowStart.label();
            do {
                if (!mappedHistory.containsKey(label)) {
                    //FIXME: custom checked exception
                    throw new IllegalArgumentException("Unable transition to [" + target.label() + "]" + " No answer was found for [" + label + "]");
                }
                final String previousLabel = label;
                final QuestionGeneric<?, ?> currentQuestion = answer(mappedHistory.get(label), false).get();
                label = currentQuestion.label();
                if (label.equals(previousLabel)) {
                    //FIXME: custom checked exception
                    throw new IllegalArgumentException("Unable transition to [" + target.label() + "]" + " Answer from the history did not solved [" + label + "]");
                }
                last = currentQuestion;
            } while (!label.equals(target.label()));
            if (mappedHistory.containsKey(target.label())) {
                answer(label, false);
            }
        }

        //TODO: transition forward:
        // * avoid circular flow e.g. for defined transition back as a target

        return result;
    }

    /**
     * Get current {@link QuestionGeneric} of the flow
     *
     * @return {@link QuestionGeneric} of the current flow
     */
    public QuestionGeneric<?, ?> get() {
        return last;
    }

    /**
     * Get previous {@link QuestionGeneric} from the flow
     *
     * @return previous {@link QuestionGeneric} and {@code null} if there is no previous entry
     */
    public QuestionGeneric<?, ?> getPrevious() {
        final List<HistoryItem> answers = getHistoryAnswered().collect(toList());
        final Set<QuestionGeneric<?, ?>> parents = getFirst().getParentsOf(get());
        return parents.stream().filter(parent -> answers.stream().anyMatch(answer -> answer.match(parent))).findFirst().orElse(parents.isEmpty() ? null : parents.iterator().next());
    }

    /**
     * Get first {@link QuestionGeneric} of the flow
     *
     * @return first {@link QuestionGeneric} of the current flow
     */
    public QuestionGeneric<?, ?> getFirst() {
        return QuestionGeneric.get(history.getFirst().getLabel());
    }

    /**
     * Check if the current flow has ended
     *
     * @return true if there is no next {@link QuestionGeneric}
     */
    public boolean isEnded() {
        return last.target().isEmpty() && getHistoryAnswered().filter(HistoryItem::isNotDraft).anyMatch(answer -> answer.match(last));
    }

    /**
     * Get history of answers
     *
     * @return all answers which were given in the context
     */
    public List<HistoryItem> getHistory() {
        return new LinkedList<>(history);
    }

    /**
     * Get history size
     *
     * @return number of given answers
     */
    public int getHistorySize() {
        return history.size();
    }

    /**
     * Solves the current {@link QuestionGeneric} of the flow
     *
     * @return {@link Survey}
     */
    public Survey answer(final Object answer) {
        return answer(answer, true);
    }

    private Survey answer(final Object answer, final boolean upDate) {
        markAsAnswered(last.label(), answer, upDate);
        Optional<QuestionGeneric<?, ?>> result = last.parseAndAnswer(answer);
        if (result.isPresent()) {
            last = result.get();
            if (upDate && !isEnded()) {
                markAsCurrent(last.label());
            }
        }
        return this;
    }

    public Map<String, Long> getDurationsMS() {
        final Map<String, Long> result = new LinkedHashMap<>();
        final AtomicReference<HistoryItem> lastTime = new AtomicReference<>(null);
        getHistoryAnswered().sorted().filter(HistoryItem::isNotDraft).forEach(item -> {
            Optional.ofNullable(lastTime.get()).ifPresent(
                    lastT -> result.put(lastT.getLabel(), Duration.between(lastT.getAnsweredAt(), item.getAnsweredAt()).toMillis())
            );
            lastTime.set(item);
        });
        return result;
    }

    private static QuestionGeneric<?, ?> findLast(final LinkedList<HistoryItem> historySorted) {
        return QuestionGeneric.get(historySorted.stream()
                .filter(HistoryItem::isNotAnswered).findFirst()
                .map(HistoryItem::getLabel)
                .orElse(historySorted.getLast().getLabel())
        );
    }

    private Stream<HistoryItem> getHistoryAnswered() {
        return history.stream().filter(HistoryItem::isAnswered);
    }

    private void markAsCurrent(final String label) {
        final HistoryItem historyItem = getOrCreateAnswer(label);
        historyItem.setDraft(true);
        historyItem.setAnsweredAt(null);
    }

    private void markAsDraft(final String label) {
        getOrCreateAnswer(label).setDraft(true);
    }

    private void markAsAnswered(final String label, final Object answer, final boolean upDate) {
        final HistoryItem historyItem = getOrCreateAnswer(label);
        if (upDate || historyItem.getAnsweredAt() == null) {
            historyItem.setAnsweredAt(LocalDateTime.now(ZoneId.of("UTC")));
        }
        historyItem.setDraft(false);
        historyItem.setAnswer(answer);
    }

    private HistoryItem getOrCreateAnswer(final String label) {
        final HistoryItem answer = new HistoryItem(label);
        int index = history.indexOf(answer);
        if (index == -1) {
            history.add(answer);
            return answer;
        }
        return history.get(index);
    }

    private Survey(final QuestionGeneric<?, ?> startQuestion) {
        assertExists(startQuestion);
        this.last = startQuestion;
        markAsCurrent(last.label());
    }

    private void assertExists(QuestionGeneric<?, ?> startQuestion) {
        if (startQuestion == null) {
            throw new IllegalArgumentException("Missing " + QuestionGeneric.class.getSimpleName() + ", given was null");
        }
    }

    private boolean runBackTransitions(final QuestionGeneric<?, ?> question) {
        final Iterator<HistoryItem> iterator = new LinkedList<>(history).descendingIterator();
        while (iterator.hasNext()) {
            HistoryItem answer = iterator.next();
            if (!answer.isAnswered()) {
                history.remove(answer);
                continue;
            } else if (!QuestionGeneric.get(answer.getLabel()).onBack(answer.getAnswer())) {
                markAsDraft(answer.getLabel());
                last = QuestionGeneric.getOrElse(answer.getLabel(), last);
                return false;
            } else if (answer.getLabel().equals(question.label())) {
                markAsDraft(answer.getLabel());
                last = QuestionGeneric.getOrElse(answer.getLabel(), last);
                return true;
            }
            markAsDraft(answer.getLabel());
            last = QuestionGeneric.getOrElse(answer.getLabel(), last);
        }
        return true;
    }

    private void assertQuestionBelongsToFlow(final QuestionGeneric<?, ?> question, final QuestionGeneric<?, ?> flowStart) {
        if (!QuestionGeneric.get(flowStart.label()).containsTarget(question)) {
            //FIXME: custom checked exception
            throw new IllegalArgumentException("[" + question.label() + "] is not a part of the flow from [" + flowStart.label() + "]");
        }
    }

    private static <T, K, U> Collector<T, ?, Map<K, U>> toLinkedMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper) {
        return Collectors.toMap(
                keyMapper,
                valueMapper,
                (u, v) -> {
                    throw new IllegalStateException(String.format("Duplicate key %s", u));
                },
                LinkedHashMap::new
        );
    }
}
