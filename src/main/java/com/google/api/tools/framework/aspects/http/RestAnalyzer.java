/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.api.tools.framework.aspects.http;

import com.google.api.tools.framework.aspects.documentation.model.ResourceAttribute;
import com.google.api.tools.framework.aspects.http.RestPatterns.MethodPattern;
import com.google.api.tools.framework.aspects.http.RestPatterns.SegmentPattern;
import com.google.api.tools.framework.aspects.http.model.CollectionAttribute;
import com.google.api.tools.framework.aspects.http.model.HttpAttribute;
import com.google.api.tools.framework.aspects.http.model.HttpAttribute.LiteralSegment;
import com.google.api.tools.framework.aspects.http.model.HttpAttribute.PathSegment;
import com.google.api.tools.framework.aspects.http.model.HttpAttribute.WildcardSegment;
import com.google.api.tools.framework.aspects.http.model.MethodKind;
import com.google.api.tools.framework.aspects.http.model.RestKind;
import com.google.api.tools.framework.aspects.http.model.RestMethod;
import com.google.api.tools.framework.model.MessageType;
import com.google.api.tools.framework.model.Method;
import com.google.api.tools.framework.model.SimpleLocation;
import com.google.api.tools.framework.model.TypeRef;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * Rest analyzer. Determines {@link RestMethod} associated with method and http binding. Also
 * computes collections and estimates their resource types.
 *
 * <p>The analyzer reports warnings regards rest conformance, but never produces an error and always
 * well-defined output for each method.
 */
class RestAnalyzer {

  private static final String REST_STYLE_RULE_NAME = "rest";
  private static final String METHOD_SHADOWED_RULE_NAME = "rest-shadowed";

  private final HttpConfigAspect aspect;
  private final Map<String, CollectionAttribute> collectionMap =
      new TreeMap<String, CollectionAttribute>();

  /**
   * Registers lint rule names used by the analyzer.
   */
  static void registerLintRuleNames(HttpConfigAspect aspect) {
    aspect.registerLintRuleName(REST_STYLE_RULE_NAME, METHOD_SHADOWED_RULE_NAME);
  }

  /**
   * Creates a rest analyzer which reports errors via the given aspect.
   */
  RestAnalyzer(HttpConfigAspect aspect) {
    this.aspect = aspect;
  }

  /**
   * Finalizes rest analysis, delivering the collections used.
   */
  List<CollectionAttribute> finalizeAndGetCollections() {
    // Compute the resource types for each collection. We need to have all collections fully
    // built before this can be done.
    //
    // In the first pass, we walk over all messages and collect information from the
    // resource attribute as derived from a doc instruction. In the second pass, for those
    // collections which still have no resource, we run a heuristic to identify the resource.
    Map<String, TypeRef> definedResources = Maps.newLinkedHashMap();

    for (TypeRef type : aspect.getModel().getSymbolTable().getDeclaredTypes()) {
      if (!type.isMessage()) {
        continue;
      }
      MessageType message = type.getMessageType();
      List<ResourceAttribute> definitions = message.getAttribute(ResourceAttribute.KEY);
      if (definitions != null) {
        for (ResourceAttribute definition : definitions) {
          TypeRef old = definedResources.put(definition.collection(), type);
          if (old != null) {
            aspect.warning(message.getLocation(),
                "Resource association of '%s' for collection '%s' overridden by '%s'. "
                + "Currently there can be only one resource associated with a collection.",
                old, definition.collection(), type);
          }
        }
      }
    }

    ImmutableList.Builder<CollectionAttribute> result = ImmutableList.builder();
    for (CollectionAttribute collection : collectionMap.values()) {
      validateCollectionAttribute(collection, collectionMap.keySet());

      TypeRef type = definedResources.get(collection.getFullName());
      if (type == null) {
        // No defined resource association, run heuristics.
        type = new ResourceTypeSelector(aspect.getModel(),
            collection.getMethods()).getCandiateResourceType();
      }
      collection.setResourceType(type);
      result.add(collection);
    }
    return result.build();
  }

  /**
   * Validates if the collection does not contain same named elements (methods and resources).
   */
  private void validateCollectionAttribute(
      CollectionAttribute collection, Set<String> allCollections) {
    if (collection == null || allCollections == null) {
      return;
    }

    for (RestMethod restMethod : collection.getMethods()) {
      if (allCollections.contains(restMethod.getRestFullMethodName())) {
        aspect.warning(
            SimpleLocation.TOPLEVEL,
            "The rpc methods and the associated http paths are not following the guidelines. As a "
            + "result the derived rest collection '%s' contains a sub collection and a "
            + "method with the same name as '%s'. This can cause a failure to generate client "
            + "library, since these names are used for generating artifacts in generated code.",
            collection.getFullName(),
            restMethod.getRestMethodName());
      }
    }
  }

  /**
   * Analyzes the given method and http config and returns a rest method.
   */
  RestMethod analyzeMethod(Method method, HttpAttribute httpConfig) {
    // First check whether this is a special method.
    RestMethod restMethod = createSpecialMethod(method, httpConfig);

    if (restMethod == null) {
      // Search for the first matching method pattern.
      MethodMatcher matcher = null;
      for (MethodPattern pattern : RestPatterns.METHOD_PATTERNS) {
        matcher = new MethodMatcher(pattern, method, httpConfig);
        if (matcher.matches) {
          break;
        }
        matcher = null;
      }
      if (matcher != null) {
        restMethod = matcher.createRestMethod();
      } else {
        // No pattern matches. Diagnose and create custom method. Even though the
        // custom method is non-conforming, it is a valid configuration.
        diagnose(method, httpConfig);
        restMethod = createCustomMethod(method, httpConfig, "");
      }
    }

    // Add method to collection.
    String collectionName = restMethod.getRestCollectionName();
    CollectionAttribute collection = collectionMap.get(collectionName);
    if (collection == null) {
      collection = new CollectionAttribute(aspect.getModel(), collectionName);
      collectionMap.put(collectionName, collection);
    }
    RestMethod oldMethod = collection.addMethod(restMethod);
    if (oldMethod != null) {
      aspect.lintWarning(METHOD_SHADOWED_RULE_NAME, restMethod.getBaseMethod(),
          "REST method '%s' from rpc method '%s' at '%s' on collection '%s' is shadowed by REST "
          + "method of same name from this rpc. The original method will not be available in "
          + "REST discovery and derived artifacts.",
          oldMethod.getRestMethodName(),
          oldMethod.getBaseMethod().getFullName(),
          oldMethod.getBaseMethod().getLocation().getDisplayString(),
          oldMethod.getRestCollectionName());
    }
    return restMethod;
  }

  // Determines whether to create a special rest method. Returns null if no special rest method.
  private RestMethod createSpecialMethod(Method method, HttpAttribute httpConfig) {
    if (httpConfig.getMethodKind() == MethodKind.NONE) {
      // Not an HTTP method. Create a dummy rest method.
      return RestMethod.create(method, RestKind.CUSTOM, "", method.getFullName());
    }
    return null;
  }

  // Create a custom rest method. If the last path segment is a literal, it will be used
  // as the verb for the custom method, otherwise the custom prefix or the rpc's name.
  private RestMethod createCustomMethod(Method method, HttpAttribute httpConfig,
      String customNamePrefix) {
    ImmutableList<PathSegment> path = httpConfig.getFlatPath();
    PathSegment lastSegment = path.get(path.size() - 1);

    // Determine base name.
    String customName = "";
    if (lastSegment instanceof LiteralSegment) {
      customName = ((LiteralSegment) lastSegment).getLiteral();
      path = path.subList(0, path.size() - 1);
    } else {
      if (aspect.getModel().getConfigVersion() > 1) {
        // From version 2 on, we generate a meaningful name here.
        customName = method.getSimpleName();
      } else if (customNamePrefix.isEmpty()){
        // Older versions use the prefix or derive from the http method.
        customName = httpConfig.getMethodKind().toString().toLowerCase();
      }
    }

    // Prepend prefix.
    if (!customNamePrefix.isEmpty()
        && !customName.toLowerCase().startsWith(customNamePrefix.toLowerCase())) {
      customName = customNamePrefix + ensureUpperCase(customName);
    }

    // Ensure effective start is lower case.
    customName = ensureLowerCase(customName);

    return RestMethod.create(method, RestKind.CUSTOM, buildCollectionName(path), customName);
  }

  private static String ensureUpperCase(String name) {
    if (!name.isEmpty() && Character.isLowerCase(name.charAt(0))) {
      return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
    return name;
  }

  private static String ensureLowerCase(String name) {
    if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
      return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
    return name;
  }

  // Create diagnosis after a unsuccessful match. We attempt to construct a list of candidates
  // which could have matched and show them to the user.
  private void diagnose(Method method, HttpAttribute httpConfig) {
    List<MethodPattern> cands = Lists.newArrayList();
    for (MethodPattern pattern : RestPatterns.METHOD_PATTERNS) {
      if (pattern.nameRegexp().matcher(method.getSimpleName()).matches()) {
        // The name matches, but other attributes not. Add a cand with the given name and
        // required attributes.
        cands.add(MethodPattern.create(pattern.httpMethod(), method.getSimpleName(),
            pattern.lastSegmentPattern(), pattern.restKind(), ""));
      }
      // Attempt to match the pattern with no name restriction.
      MethodPattern noNameRestriction = MethodPattern.create(pattern.httpMethod(), ".*",
          pattern.lastSegmentPattern(), pattern.restKind(), "");
      if (new MethodMatcher(noNameRestriction, method, httpConfig).matches) {
        cands.add(pattern);
      }
    }
    if (cands.isEmpty()) {
      cands = RestPatterns.METHOD_PATTERNS;
    }
    Object loc = method;
    if (!httpConfig.isFromIdl()) {
      loc = aspect.getLocationInConfig(
          httpConfig.getHttpRule(), httpConfig.getAnySpecifiedFieldInHttpRule());
    }
    aspect.lintWarning(REST_STYLE_RULE_NAME, loc,
        "'%s %s' is not a recognized REST pattern. Did you mean one of:\n  %s",
        MethodPattern.create(httpConfig.getMethodKind(), method.getSimpleName(), null, null, ""),
        PathSegment.toSyntax(httpConfig.getFlatPath()), Joiner.on("\n  ").join(cands));
  }

  // Builds the collection name from a path.
  private String buildCollectionName(Iterable<PathSegment> segments) {
    return Joiner.on('.').skipNulls().join(FluentIterable.from(segments).transform(
        new Function<PathSegment, String>() {
          @Override
          public String apply(PathSegment segm) {
            if (!(segm instanceof LiteralSegment)) {
              return null;
            }
            LiteralSegment literal = (LiteralSegment) segm;
            if (literal.isTrailingCustomVerb()) {
              return null;
            }
            return literal.getLiteral();
          }
        }));
  }

  /**
   * Helper class to match a method against a method pattern.
   */
  private class MethodMatcher {
    private final MethodPattern pattern;
    private final Method method;
    private final HttpAttribute httpConfig;
    private Matcher nameMatcher;
    private boolean matches;

    MethodMatcher(MethodPattern pattern, Method method, HttpAttribute httpConfig) {
      this.pattern = pattern;
      this.method = method;
      this.httpConfig = httpConfig;

      matches = false;

      // Check http method.
      if (httpConfig.getMethodKind() != pattern.httpMethod()) {
        return;
      }

      // Check name regexp.
      nameMatcher = pattern.nameRegexp().matcher(method.getSimpleName());
      if (!nameMatcher.matches()) {
        return;
      }

      // Determine match on last segment.
      List<PathSegment> flatPath = httpConfig.getFlatPath();
      PathSegment lastSegment = flatPath.get(flatPath.size() - 1);
      switch (pattern.lastSegmentPattern()) {
        case CUSTOM_VERB_WITH_COLON:
          // Allow only standard conforming custom method which uses <prefix>:<literal>.
          matches =
              lastSegment instanceof LiteralSegment
                  && ((LiteralSegment) lastSegment).isTrailingCustomVerb();
          break;
        case CUSTOM_VERB:
          // Allow both a custom verb literal and a regular literal, the latter is for supporting
          // legacy custom verbs.
          matches = lastSegment instanceof LiteralSegment;
          break;
        case VARIABLE:
          matches = lastSegment instanceof WildcardSegment;
          break;
        case LITERAL:
          matches = lastSegment instanceof LiteralSegment
              && !((LiteralSegment) lastSegment).isTrailingCustomVerb();
          break;
      }
    }

    // Creates a RestMethod from this matcher.
    private RestMethod createRestMethod() {
      if (pattern.lastSegmentPattern() == SegmentPattern.CUSTOM_VERB
          || pattern.lastSegmentPattern() == SegmentPattern.CUSTOM_VERB_WITH_COLON) {
        return createCustomMethod(method, httpConfig, pattern.customPrefix());
      }
      return RestMethod.create(method, pattern.restKind(),
          buildCollectionName(httpConfig.getFlatPath()), null);
    }
  }
}
