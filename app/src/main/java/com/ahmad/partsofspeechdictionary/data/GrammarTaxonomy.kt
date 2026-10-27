package com.ahmad.partsofspeechdictionary.data

/**
 * Initial taxonomy names transcribed from the user's PARTS OF SPEECH.pdf.
 * Categories can overlap; the app should not assume every framework uses
 * identical boundaries.
 */
object GrammarTaxonomy {
    val mainCategories = listOf(
        "Noun", "Verb", "Adverb", "Pronoun", "Adjective", "Preposition",
        "Conjunction", "Interjection", "Determiner", "Article", "Particle"
    )

    val examples = mapOf(
        "Noun" to listOf("Common noun", "Proper noun", "Concrete noun", "Abstract noun",
            "Countable/count noun", "Uncountable/mass noun", "Collective noun",
            "Compound noun", "Material/substance noun", "Verbal noun / gerund"),
        "Verb" to listOf("Main/lexical verb", "Auxiliary/helping verb", "Intransitive",
            "Transitive", "Ditransitive", "Copular/linking", "Regular verb", "Irregular verb",
            "Finite verb", "Non-finite form", "Dynamic", "Stative"),
        "Adverb" to listOf("Manner", "Time", "Place", "Direction", "Frequency",
            "Degree / intensity", "Duration", "Purpose", "Reason / result", "Interrogative",
            "Relative", "Conjunctive / linking"),
        "Pronoun" to listOf("Personal", "Possessive", "Reflexive", "Demonstrative",
            "Interrogative", "Relative", "Indefinite", "Reciprocal", "Distributive", "Negative"),
        "Adjective" to listOf("Opinion/evaluative", "Size", "Physical quality", "Shape",
            "Age", "Color", "Origin/nationality", "Material", "Quantitative", "Demonstrative",
            "Possessive", "Comparative", "Superlative"),
        "Preposition" to listOf("Simple / one-word", "Compound", "Phrase / multi-word",
            "Place/location", "Direction/motion", "Time", "Manner", "Purpose", "Cause/reason"),
        "Conjunction" to listOf("Coordinating", "Subordinating", "Correlative",
            "Copulative (additive)", "Disjunctive (alternative)", "Adversative"),
        "Interjection" to listOf("Primary (pure)", "Secondary (derived)", "Expressive / emotive",
            "Conative / volitive", "Phatic", "Cognitive", "Greetings & farewells"),
        "Determiner" to listOf("Articles", "Demonstratives", "Possessives", "Quantifiers",
            "Numerals", "Interrogative determiners", "Distributive determiners"),
        "Article" to listOf("Definite article", "Indefinite article", "Proper article",
            "Partitive article", "Negative article", "Zero article"),
        "Particle" to listOf("Case particles", "Topic particles", "Focus particles",
            "Modal particles", "Aspectual particles", "Question particles", "Discourse markers")
    )
}
