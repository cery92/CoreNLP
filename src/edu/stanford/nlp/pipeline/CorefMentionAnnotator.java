package edu.stanford.nlp.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import edu.stanford.nlp.coref.CorefCoreAnnotations;
import edu.stanford.nlp.coref.CorefProperties;
import edu.stanford.nlp.coref.data.CorefChain;
import edu.stanford.nlp.coref.data.Dictionaries;
import edu.stanford.nlp.coref.data.Mention;
import edu.stanford.nlp.coref.md.CorefMentionFinder;
import edu.stanford.nlp.coref.md.DependencyCorefMentionFinder;
import edu.stanford.nlp.coref.md.HybridCorefMentionFinder;
import edu.stanford.nlp.coref.md.RuleBasedCorefMentionFinder;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.CoreAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.HeadFinder;
import edu.stanford.nlp.trees.TreeCoreAnnotations;
import edu.stanford.nlp.util.ArraySet;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.PropertiesUtils;
import edu.stanford.nlp.util.logging.Redwood;

/**
 * This class adds mention information to an Annotation.
 *
 * After annotation each sentence will have a {@code List<Mention>} representing the Mentions in the sentence.
 *
 * The {@code List<Mention>} containing the Mentions will be put under the annotation
 * {@link edu.stanford.nlp.coref.CorefCoreAnnotations.CorefMentionsAnnotation}.
 *
 * @author heeyoung
 * @author Jason Bolton
 */

public class CorefMentionAnnotator extends TextAnnotationCreator implements Annotator {

  /**
   * A logger for this class
   */
  private static final Redwood.RedwoodChannels log = Redwood.channels(CorefMentionAnnotator.class);

  private HeadFinder headFinder;
  private CorefMentionFinder md;
  private String mdName;
  private Dictionaries dictionaries;
  private Properties corefProperties;

  private final Set<Class<? extends CoreAnnotation>> mentionAnnotatorRequirements = new HashSet<>();

  public CorefMentionAnnotator(Properties props) {
    try {
      corefProperties = props;
      //System.out.println("corefProperties: "+corefProperties);
      dictionaries = new Dictionaries(props);
      //System.out.println("got dictionaries");
      headFinder = CorefProperties.getHeadFinder(props);
      //System.out.println("got head finder");
      md = getMentionFinder(props, headFinder);
      log.info("Using mention detector type: " + mdName);
      mentionAnnotatorRequirements.addAll(Arrays.asList(
          CoreAnnotations.TokensAnnotation.class,
          CoreAnnotations.SentencesAnnotation.class,
          CoreAnnotations.PartOfSpeechAnnotation.class,
          CoreAnnotations.NamedEntityTagAnnotation.class,
          CoreAnnotations.IndexAnnotation.class,
          CoreAnnotations.TextAnnotation.class,
          CoreAnnotations.ValueAnnotation.class,
          SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class,
          SemanticGraphCoreAnnotations.EnhancedDependenciesAnnotation.class
      ));
    } catch (Exception e) {
      log.info("Error with building coref mention annotator!");
      log.info(e);
    }
  }

  @Override
  public void annotate(Annotation annotation) {
    List<CoreMap> sentences = annotation.get(CoreAnnotations.SentencesAnnotation.class);
    // TO DO: be careful, this could introduce a really hard to find bug
    // this is necessary for Chinese coreference
    // removeNested needs to be set to "false" for newswire text or big performance drop
    String docID = annotation.get(CoreAnnotations.DocIDAnnotation.class);
    if (docID == null) {
      docID = "";
    }
    if (docID.contains("nw") && (CorefProperties.conll(corefProperties)
        || corefProperties.getProperty("coref.input.type", "raw").equals("conll")) &&
            CorefProperties.getLanguage(corefProperties) == Locale.CHINESE &&
            PropertiesUtils.getBool(corefProperties,"coref.specialCaseNewswire")) {
      corefProperties.setProperty("removeNestedMentions", "false");
    } else {
      corefProperties.setProperty("removeNestedMentions", "true");
    }
    List<List<Mention>> mentions = md.findMentions(annotation, dictionaries, corefProperties);
    annotation.set(CorefCoreAnnotations.CorefMentionsAnnotation.class , new ArrayList<Mention>());
    int mentionIndex = 0;
    int currIndex = 0;
    for (CoreMap sentence : sentences) {
      List<Mention> mentionsForThisSentence = mentions.get(currIndex);
      sentence.set(CorefCoreAnnotations.CorefMentionsAnnotation.class, mentionsForThisSentence);
      annotation.get(CorefCoreAnnotations.CorefMentionsAnnotation.class).addAll(mentionsForThisSentence);
      // increment to next list of mentions
      currIndex++;
      // assign latest mentionID, annotate tokens with coref mention info
      for (Mention m : mentionsForThisSentence) {
        m.mentionID = mentionIndex;
        // go through all the tokens corresponding to this coref mention
        // annotate them with the index into the document wide coref mention list
        for (int corefMentionTokenIndex = m.startIndex ; corefMentionTokenIndex < m.endIndex ;
            corefMentionTokenIndex++) {
          CoreLabel currToken =
              sentence.get(CoreAnnotations.TokensAnnotation.class).get(corefMentionTokenIndex);
          currToken.set(CorefCoreAnnotations.CorefMentionIndexAnnotation.class, mentionIndex);
        }
        mentionIndex++;
      }
    }

  }

  private CorefMentionFinder getMentionFinder(Properties props, HeadFinder headFinder)
          throws ClassNotFoundException, IOException {

    switch (CorefProperties.mdType(props)) {
      case DEPENDENCY:
        mdName = "dependency";
        return new DependencyCorefMentionFinder(props);

      case HYBRID:
        mdName = "hybrid";
        mentionAnnotatorRequirements.add(TreeCoreAnnotations.TreeAnnotation.class);
        mentionAnnotatorRequirements.add(CoreAnnotations.BeginIndexAnnotation.class);
        mentionAnnotatorRequirements.add(CoreAnnotations.EndIndexAnnotation.class);
        return new HybridCorefMentionFinder(headFinder, props);

      case RULE:
      default:
        mentionAnnotatorRequirements.add(TreeCoreAnnotations.TreeAnnotation.class);
        mentionAnnotatorRequirements.add(CoreAnnotations.BeginIndexAnnotation.class);
        mentionAnnotatorRequirements.add(CoreAnnotations.EndIndexAnnotation.class);
        mdName = "rule";
        return new RuleBasedCorefMentionFinder(headFinder, props);
    }
  }

  @Override
  public Set<Class<? extends CoreAnnotation>> requires() {
    return mentionAnnotatorRequirements;
  }

  @Override
  public Set<Class<? extends CoreAnnotation>> requirementsSatisfied() {
    return Collections.unmodifiableSet(new ArraySet<>(Arrays.asList(
        CorefCoreAnnotations.CorefMentionsAnnotation.class,
        CoreAnnotations.ParagraphAnnotation.class,
        CoreAnnotations.SpeakerAnnotation.class,
        CoreAnnotations.UtteranceAnnotation.class
    )));
  }

}
