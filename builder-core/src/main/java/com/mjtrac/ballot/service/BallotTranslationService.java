/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.ballot.service;

import com.mjtrac.ballot.model.*;
import com.mjtrac.ballot.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides translated text for ballot generation.
 *
 * Usage:
 *   BallotTranslationService.Translator t = translationService.forLanguage("es");
 *   String title = t.contestTitle(contest);    // Spanish title or fallback to English
 *   String instr = t.msg("vote.for.one");      // "Vote por uno"
 */
@Service
public class BallotTranslationService {

    private static final Logger log =
        LoggerFactory.getLogger(BallotTranslationService.class);

    private static final String DEFAULT_LANG = "en";
    private static final String BUNDLE_BASE  =
        "i18n/ballot";   // loads i18n/ballot_en.properties etc.

    private final ContestTranslationRepository   contestTr;
    private final CandidateTranslationRepository candidateTr;

    // Cache loaded property files
    private final Map<String, Properties> msgCache = new ConcurrentHashMap<>();

    public BallotTranslationService(ContestTranslationRepository   contestTr,
                                     CandidateTranslationRepository candidateTr) {
        this.contestTr   = contestTr;
        this.candidateTr = candidateTr;
    }

    /** Return a Translator scoped to one language code. */
    public Translator forLanguage(String langCode) {
        if (langCode == null || langCode.isBlank()) langCode = DEFAULT_LANG;
        Properties msgs = loadMessages(langCode);
        Properties fallback = DEFAULT_LANG.equals(langCode)
                              ? msgs : loadMessages(DEFAULT_LANG);
        return new Translator(langCode, msgs, fallback,
                              contestTr, candidateTr);
    }

    private Properties loadMessages(String lang) {
        return msgCache.computeIfAbsent(lang, code -> {
            Properties p = new Properties();
            String path = BUNDLE_BASE + "_" + code + ".properties";
            try (InputStream is =
                     getClass().getClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                    p.load(is);
                } else {
                    log.warn("No ballot message file found for language '{}' ({})", code, path);
                }
            } catch (IOException e) {
                log.warn("Could not load ballot messages for '{}': {}", code, e.getMessage());
            }
            return p;
        });
    }

    // ── Translator ────────────────────────────────────────────────────────────

    public static class Translator {
        private final String     langCode;
        private final Properties msgs;
        private final Properties fallback;
        private final ContestTranslationRepository   contestTr;
        private final CandidateTranslationRepository candidateTr;

        Translator(String langCode, Properties msgs, Properties fallback,
                   ContestTranslationRepository contestTr,
                   CandidateTranslationRepository candidateTr) {
            this.langCode    = langCode;
            this.msgs        = msgs;
            this.fallback    = fallback;
            this.contestTr   = contestTr;
            this.candidateTr = candidateTr;
        }

        public String getLangCode() { return langCode; }

        // ── Fixed string lookup ────────────────────────────────────────────

        /** Look up a message key, with fallback to English, then the key itself. */
        public String msg(String key, Object... args) {
            String val = msgs.getProperty(key);
            if (val == null) val = fallback.getProperty(key, key);
            if (args.length > 0) {
                try { val = MessageFormat.format(val, args); }
                catch (Exception ignored) {}
            }
            return val;
        }

        public String writeIn()                  { return msg("write.in"); }
        public String voteForOne()               { return msg("vote.for.one"); }
        public String voteForUpTo(int n)         { return msg("vote.for.up.to", n); }
        public String voteForAllApprove()        { return msg("vote.for.all.approve"); }
        public String voteForMeasure()           { return msg("vote.for.measure"); }
        public String rankedChoiceInstruction()  { return msg("ranked.choice.instruction"); }
        public String sheet(int n, int total)    { return msg("sheet", n, total); }
        public String officialBallot()           { return msg("official.ballot"); }

        // ── Contest translation ────────────────────────────────────────────

        /** Contest title in this language, or base title if no translation. */
        public String contestTitle(Contest c) {
            return contestTr.findByContestIdAndLanguageCode(c.getId(), langCode)
                .map(ContestTranslation::getTitle)
                .filter(s -> s != null && !s.isBlank())
                .orElseGet(c::getTitle);
        }

        /** Contest instructions in this language (or base, or auto-generated). */
        public String contestInstructions(Contest c) {
            return contestTr.findByContestIdAndLanguageCode(c.getId(), langCode)
                .map(ContestTranslation::getInstructions)
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);  // null = let BallotGenerationService auto-generate
        }

        public String contestPreamble(Contest c) {
            return contestTr.findByContestIdAndLanguageCode(c.getId(), langCode)
                .map(ContestTranslation::getPreamble)
                .filter(s -> s != null && !s.isBlank())
                .orElse(c.getPreamble());
        }

        public String contestPostamble(Contest c) {
            return contestTr.findByContestIdAndLanguageCode(c.getId(), langCode)
                .map(ContestTranslation::getPostamble)
                .filter(s -> s != null && !s.isBlank())
                .orElse(c.getPostamble());
        }

        public String contestGroupingLabel(Contest c) {
            return contestTr.findByContestIdAndLanguageCode(c.getId(), langCode)
                .map(ContestTranslation::getGroupingLabel)
                .filter(s -> s != null && !s.isBlank())
                .orElse(c.getGroupingLabel());
        }

        // ── Candidate translation ──────────────────────────────────────────

        public String candidateName(Candidate cand) {
            return candidateTr.findByCandidateIdAndLanguageCode(cand.getId(), langCode)
                .map(CandidateTranslation::getName)
                .filter(s -> s != null && !s.isBlank())
                .orElse(cand.getName());
        }

        public String candidateExplanatoryText(Candidate cand) {
            return candidateTr.findByCandidateIdAndLanguageCode(cand.getId(), langCode)
                .map(CandidateTranslation::getExplanatoryText)
                .filter(s -> s != null && !s.isBlank())
                .orElse(cand.getExplanatoryText());
        }
    }
}
