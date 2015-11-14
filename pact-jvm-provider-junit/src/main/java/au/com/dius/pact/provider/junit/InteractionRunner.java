package au.com.dius.pact.provider.junit;

import au.com.dius.pact.model.Interaction;
import au.com.dius.pact.model.Pact;
import au.com.dius.pact.provider.junit.target.Target;
import au.com.dius.pact.provider.junit.target.TestTarget;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.internal.runners.model.ReflectiveCallable;
import org.junit.internal.runners.statements.Fail;
import org.junit.internal.runners.statements.RunAfters;
import org.junit.internal.runners.statements.RunBefores;
import org.junit.rules.RunRules;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.*;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.stream.Collectors.toList;
import static org.junit.internal.runners.rules.RuleMemberValidator.RULE_METHOD_VALIDATOR;
import static org.junit.internal.runners.rules.RuleMemberValidator.RULE_VALIDATOR;

/**
 * Internal class to support pact test running
 * <p>
 * Developed with {@link org.junit.runners.BlockJUnit4ClassRunner} in mind
 */
class InteractionRunner extends Runner {
    private final TestClass testClass;
    private final Pact pact;

    private final ConcurrentHashMap<Interaction, Description> childDescriptions = new ConcurrentHashMap<>();

    public InteractionRunner(final TestClass testClass, final Pact pact) throws InitializationError {
        this.testClass = testClass;
        this.pact = pact;

        validate();
    }

    @Override
    public Description getDescription() {
        final Description description = Description.createSuiteDescription(pact.consumer().name());
        scala.collection.JavaConversions.seqAsJavaList(pact.interactions()).stream().map(this::describeChild).forEach(description::addChild);
        return description;
    }

    protected Description describeChild(final Interaction interaction) {
        return childDescriptions.computeIfAbsent(interaction, i -> Description.createTestDescription(pact.consumer().name(), i.description()));
    }

    // Validation
    protected void validate() throws InitializationError {
        final List<Throwable> errors = new ArrayList<>();

        validatePublicVoidNoArgMethods(Before.class, false, errors);
        validatePublicVoidNoArgMethods(After.class, false, errors);
        validatePublicVoidNoArgMethods(State.class, false, errors);
        validateConstructor(errors);
        validateTestTarget(errors);
        validateRules(errors);

        if (!errors.isEmpty()) {
            throw new InitializationError(errors);
        }
    }

    protected void validatePublicVoidNoArgMethods(final Class<? extends Annotation> annotation, final boolean isStatic, final List<Throwable> errors) {
        testClass.getAnnotatedMethods(annotation).stream().forEach(method -> method.validatePublicVoidNoArg(isStatic, errors));
    }

    protected void validateConstructor(final List<Throwable> errors) {
        if (!hasOneConstructor()) {
            errors.add(new Exception("Test class should have exactly one public constructor"));
        }
        if (!testClass.isANonStaticInnerClass()
                && hasOneConstructor()
                && (testClass.getOnlyConstructor().getParameterTypes().length != 0)) {
            errors.add(new Exception("Test class should have exactly one public zero-argument constructor"));
        }
    }

    protected boolean hasOneConstructor() {
        return testClass.getJavaClass().getConstructors().length == 1;
    }

    protected void validateTestTarget(final List<Throwable> errors) {
        final List<FrameworkField> annotatedFields = testClass.getAnnotatedFields(TestTarget.class);
        if (annotatedFields.size() != 1) {
            errors.add(new Exception("Test class should have exactly one field annotated with " + TestTarget.class.getName()));
        } else if (!Target.class.isAssignableFrom(annotatedFields.get(0).getType())) {
            errors.add(new Exception("Field annotated with " + TestTarget.class.getName() + " should implement " + Target.class.getName() + " interface"));
        }
    }

    protected void validateRules(final List<Throwable> errors) {
        RULE_VALIDATOR.validate(testClass, errors);
        RULE_METHOD_VALIDATOR.validate(testClass, errors);
    }

    // Running
    public void run(final RunNotifier notifier) {
        for (final Interaction interaction : scala.collection.JavaConversions.seqAsJavaList(pact.interactions())) {
            final Description description = describeChild(interaction);
            notifier.fireTestStarted(description);
            try {
                interactionBlock(interaction).evaluate();
            } catch (final Throwable e) {
                notifier.fireTestFailure(new Failure(description, e));
            } finally {
                notifier.fireTestFinished(description);
            }
        }
    }

    protected Object createTest() throws Exception {
        return testClass.getOnlyConstructor().newInstance();
    }

    protected Statement interactionBlock(final Interaction interaction) {
        //1. prepare object
        //2. get Target
        //3. run Rule`s
        //4. run Before`s
        //5. run OnStateChange`s
        //6. run test
        //7. run After`s
        final Object test;
        try {
            test = new ReflectiveCallable() {
                @Override
                protected Object runReflectiveCall() throws Throwable {
                    return createTest();
                }
            }.run();
        } catch (Throwable e) {
            return new Fail(e);
        }
        final Target target = testClass.getAnnotatedFieldValues(test, TestTarget.class, Target.class).get(0);

        Statement statement = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                target.testInteraction(interaction);
            }
        };
        statement = withStateChanges(interaction, test, statement);
        statement = withBefores(interaction, test, statement);
        statement = withRules(interaction, test, statement);
        statement = withAfters(interaction, test, statement);
        return statement;
    }

    protected Statement withStateChanges(final Interaction interaction, final Object target, final Statement statement) {
        if (interaction.providerState().nonEmpty()) {
            final String state = interaction.providerState().get();
            final List<FrameworkMethod> onStateChange = testClass.getAnnotatedMethods(State.class)
                    .stream()
                    .filter(ann -> ArrayUtils.contains(ann.getAnnotation(State.class).value(), state))
                    .collect(toList());
            return onStateChange.isEmpty() ? statement : new RunBefores(statement, onStateChange, target);
        } else {
            return statement;
        }
    }

    protected Statement withBefores(final Interaction interaction, final Object target, final Statement statement) {
        final List<FrameworkMethod> befores = testClass.getAnnotatedMethods(Before.class);
        return befores.isEmpty() ? statement : new RunBefores(statement, befores, target);
    }

    protected Statement withAfters(final Interaction interaction, final Object target, final Statement statement) {
        final List<FrameworkMethod> afters = testClass.getAnnotatedMethods(After.class);
        return afters.isEmpty() ? statement : new RunAfters(statement, afters, target);
    }

    protected Statement withRules(final Interaction interaction, final Object target, final Statement statement) {
        final List<TestRule> testRules = testClass.getAnnotatedMethodValues(target, Rule.class, TestRule.class);
        testRules.addAll(testClass.getAnnotatedFieldValues(target, Rule.class, TestRule.class));
        return testRules.isEmpty() ? statement : new RunRules(statement, testRules, describeChild(interaction));
    }
}
