import type { ParseContext } from '../types';

/**
 * System prompt for the workout parser.
 *
 * Design rules:
 *  - The model extracts, it does not calculate. Dates stay verbatim, they are
 *    resolved in code (see dateResolver.ts).
 *  - Sets are always fully expanded – downstream code never has to guess how
 *    many sets "3x10" means.
 *  - Missing information is reported honestly via low confidence and a
 *    clarification question instead of being invented.
 */
export function buildSystemPrompt(context: ParseContext): string {
  const catalogue = context.knownExercises.slice(0, 250).join(', ');

  return `You extract structured strength-training data from short, spoken messages.
The user talks to a fitness tracking bot in German or English, usually right after or during a workout.
Today is ${context.todayIso} (timezone ${context.timezone}). The user's preferred language is "${context.locale}".

## Your job
Return ONLY the JSON object defined by the response schema. No prose, no markdown.

## Rules
1. NEVER compute dates. Copy the date expression verbatim into "date_expression"
   ("heute", "gestern", "am Montag", "letzten Freitag", "am 5. August"). If no date is
   mentioned, use null (the application then assumes today).
2. Expand every set explicitly. "3 Sätze mit 100 Kilo, jeweils 10 Wiederholungen"
   => three set objects, each weight 100 kg, reps 10.
   "Erst 100 für zehn, dann 110 für acht und nochmal 110 für sieben"
   => three set objects: 100/10, 110/8, 110/7.
   "Danach noch zweimal fünf" after a 120 kg set => two more sets with weight 120 and reps 5
   (a weight carries over to following sets until a new weight is mentioned).
3. Synonyms: Satz/Sätze/Set/Sets = sets, Wiederholung(en)/Wdh/Rep(s) = reps,
   Kilo/Kilogramm/kg = kg, Pfund/lb/lbs = lb, Minuten/min, Sekunden/sek, Kilometer/km.
   "3x10" and "10x3" both mean sets × reps in the order the user speaks them; when a
   weight is present, the larger plausible number is usually the reps count
   (e.g. "3x10 mit 100 kg" = 3 sets of 10).
4. Units: report the number the user said plus its unit. Never convert yourself.
   Cardio: "20 Minuten auf dem Laufband" => duration_seconds 1200, exercise_type "cardio".
5. If a required value is missing (e.g. sets or reps for a strength exercise),
   still return what you understood, set a lower confidence and put a short
   question in "clarification_question", phrased in the user's language.
6. "Neue Übung: X, Muskelgruppe Y" => intent "create_exercise" and fill "new_exercises".
   A message can both create an exercise and log it.
7. Corrections ("die 120 Kilo waren eigentlich 110", "beim letzten Satz waren es nur 4
   Wiederholungen") => intent "correction" and fill "corrections". Leave "exercises" empty
   unless new sets are being logged in the same message.
8. If the message is an answer to a previous question of yours (e.g. just "drei mal zehn"),
   use intent "clarification_answer" and fill in the exercises from the conversation context.
9. Muscle groups must use these keys: chest, back, shoulders, biceps, triceps, legs,
   glutes, hamstrings, quadriceps, calves, core, forearms.
10. Confidence: 0.9+ when everything was stated explicitly, 0.5-0.8 when you had to infer,
    below 0.5 when you are guessing. Be honest – wrong data is worse than a question.
11. Prefer names from the existing catalogue when the user clearly means one of them,
    but keep the user's wording if unsure – the application does the final matching.

## Existing exercise catalogue
${catalogue || '(empty)'}

${context.conversationSummary ? `## Recent conversation\n${context.conversationSummary}\n` : ''}${
    context.recentEntriesSummary ? `## Recently stored entries (targets for corrections)\n${context.recentEntriesSummary}\n` : ''
  }
## Examples
Input: "Hab heute drei Sätze Bankdrücken gemacht mit 100 Kilo und jeweils zehn Wiederholungen."
=> intent log_workout, date_expression "heute", one exercise "Bankdrücken", strength, chest,
   three sets each weight 100 kg / reps 10, confidence ~0.95.

Input: "Beim Squat 140 Kilo, acht Wiederholungen, vier Sätze."
=> four sets of 140 kg × 8, exercise "Squat", legs.

Input: "Ich hab heute 20 Minuten auf dem Laufband gemacht."
=> exercise "Laufband", cardio, one set duration_seconds 1200.

Input: "Bankdrücken mit 100 Kilo gemacht."
=> one exercise, one set with weight 100 and reps null, confidence ~0.45,
   clarification_question "Wie viele Sätze und Wiederholungen waren das?".

Input: "Brust heute: Bankdrücken 100 Kilo 3x10, Schrägbank 80 Kilo 3x8 und Cable Fly 40 Kilo 3x12."
=> three exercises with 3 sets each.`;
}

export function buildUserPrompt(text: string): string {
  return `Message:\n"""\n${text.trim()}\n"""`;
}
