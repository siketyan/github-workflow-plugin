package com.github.yunabraska.githubworkflow.services;

import com.github.yunabraska.githubworkflow.helper.PsiElementHelper;
import com.github.yunabraska.githubworkflow.model.GitHubAction;
import com.github.yunabraska.githubworkflow.model.IconRenderer;
import com.github.yunabraska.githubworkflow.model.SimpleElement;
import com.github.yunabraska.githubworkflow.model.SyntaxAnnotation;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLSequenceItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.yunabraska.githubworkflow.helper.GitHubWorkflowConfig.*;
import static com.github.yunabraska.githubworkflow.helper.HighlightAnnotatorHelper.*;
import static com.github.yunabraska.githubworkflow.helper.PsiElementHelper.*;
import static com.github.yunabraska.githubworkflow.model.NodeIcon.ICON_TEXT_VARIABLE;
import static com.github.yunabraska.githubworkflow.model.SyntaxAnnotation.createAnnotation;
import static com.intellij.lang.annotation.HighlightSeverity.INFORMATION;
import static java.util.Optional.ofNullable;

public class HighlightAnnotator implements Annotator {

    public static final Pattern CARET_BRACKET_ITEM_PATTERN = Pattern.compile("[\\s|\\t^{]\\b(\\w++(?:\\.\\w++)++)[\\s|\\t$}]");

    @Override
    public void annotate(@NotNull final PsiElement psiElement, @NotNull final AnnotationHolder holder) {
        if (psiElement.isValid()) {
            processPsiElement(holder, psiElement);
        }
    }

    public static void processPsiElement(final AnnotationHolder holder, final PsiElement psiElement) {
        toYAMLKeyValue(psiElement).ifPresent(element -> {
            switch (element.getKeyText()) {
                case FIELD_USES -> usesHandler(holder, element);
                case FIELD_WITH -> withHandler(holder, element);
                case FIELD_NEEDS -> needsHandler(holder, element);
                case FIELD_RUN -> runHandler(holder, element);
                case FIELD_OUTPUTS -> outputsHandler(holder, element);
                default -> {
                    // No Action
                }
            }
        });

        // action.yaml
//        ofNullable(psiElement)
//                .filter(LeafPsiElement.class::isInstance)
//                .map(LeafPsiElement.class::cast)
//                .map(PsiElement::getParent)
//                .filter(YAMLKeyValue.class::isInstance)
//                .map(YAMLKeyValue.class::cast)
//                .filter(with -> FIELD_WITH.equals(with.getKeyText()))
//                .filter(with -> psiElement.getText().equals(FIELD_WITH))
//                .ifPresent(with -> withHandler(holder, psiElement));

        // GENERAL
        variableHandler(holder, psiElement);
    }

    private static void runHandler(final AnnotationHolder holder, final YAMLKeyValue element) {
        // SHOW Output Env && Output Variable declaration
        Stream.of(
                parseEnvVariables(element),
                parseOutputVariables(element)
        ).flatMap(Collection::stream).collect(Collectors.groupingBy(SimpleElement::startIndexOffset)).forEach((integer, elements) -> ofNullable(getFirstChild(elements)).ifPresent(lineElement -> holder
                .newSilentAnnotation(INFORMATION)
                .range(lineElement.range())
                .gutterIconRenderer(new IconRenderer(null, element, ICON_TEXT_VARIABLE))
                .create()
        ));
    }


    @SuppressWarnings("DataFlowIssue")
    private static void usesHandler(final AnnotationHolder holder, final YAMLKeyValue element) {
        final List<SyntaxAnnotation> result = new ArrayList<>();
        ofNullable(element)
                .map(GitHubActionCache::getAction)
                .ifPresentOrElse(action -> {
                    if (action.isResolved() && !action.isLocal()) {
                        result.add(newReloadAction(action));
                    }
                    if (action.isResolved() && action.isLocal()) {
                        final String tooltip = String.format("Open declaration (%s)", Arrays.stream(KeymapUtil.getActiveKeymapShortcuts("GotoDeclaration").getShortcuts())
                                .limit(2)
                                .map(KeymapUtil::getShortcutText)
                                .collect(Collectors.joining(", "))
                        );
                        holder.newAnnotation(HighlightSeverity.INFORMATION, tooltip)
                                .range(getTextElement(element).orElse(null))
                                .textAttributes(DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE)
                                .tooltip(tooltip)
                                .create();
                        result.add(newJumpToFile(action));
                    }
                    if (!action.isResolved()) {
                        result.add(newSuppressAction(action));
                        if (!action.isSuppressed()) {
                            result.add(action.isLocal() ? deleteInvalidAction(element) : newUnresolvedAction(element));
                        }
                    }
                }, () -> result.add(newUnresolvedAction(element))); //FIXME: is this a valid state?
        addAnnotation(holder, element, result);
    }

    private static void needsHandler(final AnnotationHolder holder, final YAMLKeyValue element) {
        final List<PsiElement> neededJobs = getTextElements(element);
        if (!neededJobs.isEmpty()) {
            final String currentJobName = getParentJob(element).map(YAMLKeyValue::getKeyText).orElse("");
            final List<String> previousJobNames = getAllJobs(element).stream().map(YAMLKeyValue::getKeyText).takeWhile(jobName -> !currentJobName.equals(jobName)).toList();
            neededJobs.forEach(neededJob -> {
                final String jobId = removeQuotes(neededJob.getText());
                if (!previousJobNames.contains(jobId)) {
                    // INVALID JOB_ID
                    addAnnotation(holder, neededJob, new SyntaxAnnotation(
                            "Remove invalid jobId [" + jobId + "] - this jobId doesn't match any previous job",
                            null,
                            deleteElementAction(neededJob.getTextRange())
                    ));
                }
            });

        }

    }

    private static void withHandler(final AnnotationHolder holder, final PsiElement element) {
        getParentStepOrJob(element)
                .flatMap(step -> PsiElementHelper.getChild(step, FIELD_USES))
                .map(GitHubActionCache::getAction)
                .filter(GitHubAction::isResolved)
                .map(GitHubAction::freshInputs)
                .map(Map::keySet)
                .ifPresent(inputs -> PsiElementHelper.getChildren(element).forEach(kvInput -> {
                    if (!inputs.contains(kvInput.getKeyText())) {
                        addAnnotation(holder, kvInput, new SyntaxAnnotation(
                                "Delete invalid input [" + kvInput.getKeyText() + "]",
                                null,
                                deleteElementAction(kvInput.getTextRange())
                        ));
                    }
                }));
    }


    private static void outputsHandler(final AnnotationHolder holder, final PsiElement psiElement) {
        getParentJob(psiElement).ifPresent(job -> {
            final List<YAMLKeyValue> outputs = PsiElementHelper.getChildren(psiElement).stream().toList();
            final String workflowText = PsiElementHelper.getChild(psiElement.getContainingFile(), FIELD_JOBS).map(PsiElement::getText).orElse("");
            final List<String> workflowOutputs = PsiElementHelper.getChild(psiElement.getContainingFile(), FIELD_ON)
                    .map(on -> getAllElements(on, FIELD_OUTPUTS))
                    .map(list -> list.stream().flatMap(keyValue -> PsiElementHelper.getChildren(keyValue).stream().map(output -> getText(output, "value").orElse(""))).toList())
                    .orElseGet(Collections::emptyList);
            outputs.stream().filter(output -> {
                final String outputKey = output.getKeyText();
                return workflowOutputs.stream().noneMatch(
                        wo -> wo.contains(FIELD_JOBS + "." + job.getKeyText() + "." + FIELD_OUTPUTS + "." + outputKey + " ") || wo.contains(FIELD_JOBS + "." + job.getKeyText() + "." + FIELD_OUTPUTS + "." + outputKey + "}")
                ) && !workflowText.contains(FIELD_NEEDS + "." + job.getKeyText() + "." + FIELD_OUTPUTS + "." + outputKey + " ") && !workflowText.contains(FIELD_NEEDS + "." + job.getKeyText() + "." + FIELD_OUTPUTS + "." + outputKey + "}");
            }).forEach(output -> new SyntaxAnnotation(
                    "Unused [" + output.getKeyText() + "]",
                    null,
                    HighlightSeverity.WEAK_WARNING,
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    deleteElementAction(output.getTextRange()),
                    true
            ).createAnnotation(output, output.getTextRange(), holder));

        });
    }


    private static void variableHandler(final AnnotationHolder holder, final PsiElement psiElement) {
        getParent(psiElement, FIELD_RUN)
                .or(() -> getParent(psiElement, FIELD_ID))
                .or(() -> getParent(psiElement, "name"))
                .or(() -> getParent(psiElement, "value").isPresent() ? getParent(psiElement, FIELD_OUTPUTS) : Optional.empty())
                .or(() -> getParent(psiElement, FIELD_WITH))
                .or(() -> getParent(psiElement, FIELD_ENVS))
                .or(() -> getParent(psiElement, FIELD_OUTPUTS))
                .ifPresent(parent -> {
                    final Matcher matcher = CARET_BRACKET_ITEM_PATTERN.matcher(psiElement.getText());
                    while (matcher.find()) {
                        final String[] parts = Arrays.stream(matcher.group().split("\\."))
                                .map(s -> s.replace("{", ""))
                                .map(s -> s.replace("}", ""))
                                .map(String::trim)
                                .toArray(String[]::new);
                        final String scope = parts[0];
                        switch (scope) {
                            case FIELD_INPUTS ->
                                    ifEnoughItems(holder, psiElement, parts, 2, 2, inputId -> isDefinedItem0(psiElement, holder, matcher, inputId, listInputs(psiElement).stream().map(SimpleElement::key).toList()));
                            case FIELD_SECRETS -> ifEnoughItems(holder, psiElement, parts, 2, 2, secretId -> {
                                final List<String> secrets = listSecrets(psiElement).stream().map(SimpleElement::key).toList();
                                if (!secrets.contains(secretId)) {
                                    final TextRange textRange = simpleTextRange(psiElement, matcher, secretId);
                                    createAnnotation(psiElement, textRange, holder, secrets.stream().map(secret -> new SyntaxAnnotation(
                                            "Replace [" + secretId + "] with [" + secret + "] - if it is not provided at runtime",
                                            null,
                                            HighlightSeverity.WEAK_WARNING,
                                            ProblemHighlightType.WEAK_WARNING,
                                            replaceAction(textRange, secret),
                                            true
                                    )).toList());
                                }
                            });
                            case FIELD_ENVS ->
                                    ifEnoughItems(holder, psiElement, parts, 2, -1, envId -> isDefinedItem0(psiElement, holder, matcher, envId, listEnvs(psiElement).stream().map(SimpleElement::key).toList()));
                            case FIELD_GITHUB ->
                                    ifEnoughItems(holder, psiElement, parts, 2, -1, envId -> isDefinedItem0(psiElement, holder, matcher, envId, new ArrayList<>(DEFAULT_VALUE_MAP.get(FIELD_GITHUB).get().keySet())));
                            case FIELD_RUNNER ->
                                    ifEnoughItems(holder, psiElement, parts, 2, 2, runnerId -> isDefinedItem0(psiElement, holder, matcher, runnerId, new ArrayList<>(DEFAULT_VALUE_MAP.get(FIELD_RUNNER).get().keySet())));
                            case FIELD_STEPS -> ifEnoughItems(holder, psiElement, parts, 4, 4, stepId -> {
                                final List<YAMLSequenceItem> steps = listSteps(psiElement);
                                if (isDefinedItem0(psiElement, holder, matcher, stepId, steps.stream().map(step -> getText(step, FIELD_ID).orElse(null)).filter(Objects::nonNull).toList()) && isField2Valid(psiElement, holder, matcher, parts[2])) {
                                    final List<String> outputs = listStepOutputs(steps.stream().filter(step -> getText(step, FIELD_ID).filter(id -> id.equals(stepId)).isPresent()).findFirst().orElse(null)).stream().map(SimpleElement::key).toList();
                                    isValidItem3(psiElement, holder, matcher, parts[3], outputs);

                                }
                            });
                            case FIELD_JOBS -> ifEnoughItems(holder, psiElement, parts, 4, 4, jobId -> {
                                final List<YAMLKeyValue> jobs = listJobs(psiElement);
                                if (isDefinedItem0(psiElement, holder, matcher, jobId, jobs.stream().map(YAMLKeyValue::getKeyText).toList()) && isField2Valid(psiElement, holder, matcher, parts[2])) {
                                    final List<String> outputs = listJobOutputs(jobs.stream().filter(job -> jobId.equals(job.getKeyText())).findFirst().orElse(null)).stream().map(SimpleElement::key).toList();
                                    isValidItem3(psiElement, holder, matcher, parts[3], outputs);
                                }
                            });
                            case FIELD_NEEDS -> ifEnoughItems(holder, psiElement, parts, 4, 4, jobId -> {
                                final List<String> jobIds = listJobNeeds(psiElement);
                                if (isDefinedItem0(psiElement, holder, matcher, jobId, jobIds) && isField2Valid(psiElement, holder, matcher, parts[2])) {
                                    final List<String> outputs = listJobOutputs(listAllJobs(psiElement).stream().filter(job -> jobId.equals(job.getKeyText())).findFirst().orElse(null)).stream().map(SimpleElement::key).toList();
                                    isValidItem3(psiElement, holder, matcher, parts[3], outputs);
                                }
                            });
                            default -> {
                                // ignored
                            }
                        }
                    }
                });
    }
}