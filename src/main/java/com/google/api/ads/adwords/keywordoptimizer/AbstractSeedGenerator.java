// Copyright 2016 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.api.ads.adwords.keywordoptimizer;

import com.google.api.ads.adwords.axis.v201509.cm.Keyword;
import com.google.api.ads.adwords.axis.v201509.cm.KeywordMatchType;
import com.google.api.ads.adwords.axis.v201509.cm.Money;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Base seed generator class providing some of the general functionality used by specific seed
 * generators.
 */
public abstract class AbstractSeedGenerator extends AdditionalInfoHolder implements SeedGenerator {
  protected final List<KeywordMatchType> matchTypes;
  
  /**
   * Creates a new {@link AbstractSeedGenerator} using a given CPC.
   * 
   * @param maxCpc the maximum cpc (cost per click) as a {@link Money} object
   */
  public AbstractSeedGenerator(Money maxCpc) {
    super(maxCpc);
    this.matchTypes = new ArrayList<KeywordMatchType>();
  }

  /**
   * Creates a new {@link AbstractSeedGenerator}.
   */
  public AbstractSeedGenerator() {
    this(null);
  }

  /**
   * Method for generating plain text keywords. This has to be implemented by derived classes. The
   * {@link AbstractSeedGenerator} will use these plain texts to create actual {@link
   * KeywordCollection} using the specified additional criteria and match types.
   *
   * @return a {@link Collection} a plain text keywords
   * @throws KeywordOptimizerException in case of an error retrieving seed keywords
   */
  protected abstract Collection<String> getKeywords() throws KeywordOptimizerException;

  @Override
  public KeywordCollection generate() throws KeywordOptimizerException {
    Collection<String> keywords = getKeywords();

    KeywordCollection keywordCollection = new KeywordCollection(getMaxCpc());
    keywordCollection.addAdditionalCriteria(getAdditionalCriteria());

    for (String keywordText : keywords) {
      for (KeywordMatchType matchType : matchTypes) {
        Keyword keyword = KeywordOptimizerUtil.createKeyword(keywordText, matchType);
        keywordCollection.add(new KeywordInfo(keyword, null, null));
      }
    }

    return keywordCollection;
  }

  /**
   * Adds a match type for the keywords.
   * 
   * @param matchType the matchType to add
   */
  @Override
  public void addMatchType(KeywordMatchType matchType) {
    matchTypes.add(matchType);
  }
}
