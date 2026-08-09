import { ExerciseType, MuscleRole, PrismaClient, DataSource } from '@prisma/client';
import { normalizeName } from '../src/ai/exerciseMatcher';

/**
 * Seeds muscle groups and a catalogue of common exercises.
 *
 * The German names and the alias list are what makes voice input work: the
 * matcher compares the transcript against name, nameDe and every alias, so
 * "Bankdrücken", "Bank drücken" and "Benchpress" all resolve to Bench Press.
 *
 * Idempotent – running it again updates instead of duplicating.
 */

const prisma = new PrismaClient();

interface MuscleGroupSeed {
  key: string;
  nameEn: string;
  nameDe: string;
  parentKey?: string | null;
}

const MUSCLE_GROUPS: MuscleGroupSeed[] = [
  { key: 'chest', nameEn: 'Chest', nameDe: 'Brust' },
  { key: 'back', nameEn: 'Back', nameDe: 'Rücken' },
  { key: 'shoulders', nameEn: 'Shoulders', nameDe: 'Schultern' },
  { key: 'arms', nameEn: 'Arms', nameDe: 'Arme' },
  { key: 'biceps', nameEn: 'Biceps', nameDe: 'Bizeps', parentKey: 'arms' },
  { key: 'triceps', nameEn: 'Triceps', nameDe: 'Trizeps', parentKey: 'arms' },
  { key: 'forearms', nameEn: 'Forearms', nameDe: 'Unterarme', parentKey: 'arms' },
  { key: 'legs', nameEn: 'Legs', nameDe: 'Beine' },
  { key: 'quadriceps', nameEn: 'Quadriceps', nameDe: 'Quadrizeps', parentKey: 'legs' },
  { key: 'hamstrings', nameEn: 'Hamstrings', nameDe: 'Beinbeuger', parentKey: 'legs' },
  { key: 'glutes', nameEn: 'Glutes', nameDe: 'Gesäß', parentKey: 'legs' },
  { key: 'calves', nameEn: 'Calves', nameDe: 'Waden', parentKey: 'legs' },
  { key: 'core', nameEn: 'Core', nameDe: 'Rumpf' },
  { key: 'cardio', nameEn: 'Cardio', nameDe: 'Ausdauer' },
];

interface ExerciseSeed {
  name: string;
  nameDe: string;
  type?: ExerciseType;
  equipment?: string;
  primary: string[];
  secondary?: string[];
  aliases: string[];
}

const EXERCISES: ExerciseSeed[] = [
  // --- Chest ---------------------------------------------------------------
  {
    name: 'Bench Press', nameDe: 'Bankdrücken', equipment: 'Langhantel',
    primary: ['chest'], secondary: ['triceps', 'shoulders'],
    aliases: ['bankdruecken', 'bank druecken', 'benchpress', 'bench', 'flachbankdruecken', 'langhantel bankdruecken'],
  },
  {
    name: 'Incline Bench Press', nameDe: 'Schrägbankdrücken', equipment: 'Langhantel',
    primary: ['chest'], secondary: ['shoulders', 'triceps'],
    aliases: ['schraegbankdruecken', 'schraegbank', 'schraeg bankdruecken', 'incline bench', 'incline press'],
  },
  {
    name: 'Dumbbell Bench Press', nameDe: 'Kurzhantel-Bankdrücken', equipment: 'Kurzhantel',
    primary: ['chest'], secondary: ['triceps', 'shoulders'],
    aliases: ['kurzhantel bankdruecken', 'kh bankdruecken', 'dumbbell press', 'dumbbell bench'],
  },
  {
    name: 'Cable Fly', nameDe: 'Kabelzug-Fliegende', equipment: 'Kabelzug',
    primary: ['chest'],
    aliases: ['cable fly', 'cable flys', 'kabelzug fliegende', 'fliegende', 'butterfly kabel', 'cable crossover'],
  },
  {
    name: 'Pec Deck', nameDe: 'Butterfly', equipment: 'Maschine',
    primary: ['chest'],
    aliases: ['pec deck', 'butterfly', 'peck deck', 'brustmaschine'],
  },
  {
    name: 'Push Up', nameDe: 'Liegestütze', type: ExerciseType.BODYWEIGHT,
    primary: ['chest'], secondary: ['triceps', 'core'],
    aliases: ['liegestuetze', 'liegestuetz', 'pushup', 'push ups', 'push-ups'],
  },

  // --- Back ----------------------------------------------------------------
  {
    name: 'Lat Pulldown', nameDe: 'Latzug', equipment: 'Kabelzug',
    primary: ['back'], secondary: ['biceps'],
    aliases: ['latzug', 'lat zug', 'lat pulldown', 'pulldown', 'latziehen'],
  },
  {
    name: 'Pull Up', nameDe: 'Klimmzug', type: ExerciseType.BODYWEIGHT,
    primary: ['back'], secondary: ['biceps'],
    aliases: ['klimmzug', 'klimmzuege', 'pullup', 'pull ups', 'chin up', 'chinup'],
  },
  {
    name: 'Barbell Row', nameDe: 'Langhantelrudern', equipment: 'Langhantel',
    primary: ['back'], secondary: ['biceps'],
    aliases: ['langhantelrudern', 'langhantel rudern', 'rudern', 'barbell row', 'bent over row', 'vorgebeugtes rudern'],
  },
  {
    name: 'Cable Row', nameDe: 'Kabelrudern', equipment: 'Kabelzug',
    primary: ['back'], secondary: ['biceps'],
    aliases: ['kabelrudern', 'kabelzug rudern', 'seated row', 'sitzendes rudern', 'cable row', 'ruderzug'],
  },
  {
    name: 'T-Bar Row', nameDe: 'T-Bar-Rudern', equipment: 'T-Bar',
    primary: ['back'], secondary: ['biceps'],
    aliases: ['t bar rudern', 'tbar row', 't-bar row', 't bar'],
  },
  {
    name: 'Deadlift', nameDe: 'Kreuzheben', equipment: 'Langhantel',
    primary: ['back'], secondary: ['hamstrings', 'glutes'],
    aliases: ['kreuzheben', 'deadlift', 'deadlifts', 'kreuz heben'],
  },

  // --- Shoulders -----------------------------------------------------------
  {
    name: 'Shoulder Press', nameDe: 'Schulterdrücken', equipment: 'Kurzhantel',
    primary: ['shoulders'], secondary: ['triceps'],
    aliases: ['schulterdruecken', 'schulter druecken', 'overhead press', 'ohp', 'militarypress', 'military press', 'schulterpresse'],
  },
  {
    name: 'Lateral Raise', nameDe: 'Seitheben', equipment: 'Kurzhantel',
    primary: ['shoulders'],
    aliases: ['seitheben', 'seit heben', 'lateral raise', 'lateral raises', 'seitliches heben', 'cable lateral raise'],
  },
  {
    name: 'Front Raise', nameDe: 'Frontheben', equipment: 'Kurzhantel',
    primary: ['shoulders'],
    aliases: ['frontheben', 'front raise', 'front raises'],
  },
  {
    name: 'Rear Delt Fly', nameDe: 'Reverse Butterfly', equipment: 'Maschine',
    primary: ['shoulders'], secondary: ['back'],
    aliases: ['reverse butterfly', 'rear delt fly', 'reverse fly', 'vorgebeugtes seitheben', 'rear delts'],
  },

  // --- Arms ----------------------------------------------------------------
  {
    name: 'Biceps Curl', nameDe: 'Bizepscurl', equipment: 'Kurzhantel',
    primary: ['biceps'],
    aliases: ['bizepscurl', 'bizeps curl', 'bizeps curls', 'curls', 'curl', 'biceps curl', 'langhantelcurl', 'sz curl'],
  },
  {
    name: 'Hammer Curl', nameDe: 'Hammercurl', equipment: 'Kurzhantel',
    primary: ['biceps'], secondary: ['forearms'],
    aliases: ['hammercurl', 'hammer curl', 'hammer curls'],
  },
  {
    name: 'Triceps Pushdown', nameDe: 'Trizepsdrücken am Kabel', equipment: 'Kabelzug',
    primary: ['triceps'],
    aliases: ['trizepsdruecken', 'trizeps druecken', 'pushdown', 'triceps pushdown', 'trizeps kabel', 'pushdowns'],
  },
  {
    name: 'Skull Crusher', nameDe: 'Stirndrücken', equipment: 'SZ-Stange',
    primary: ['triceps'],
    aliases: ['stirndruecken', 'skull crusher', 'skullcrusher', 'french press', 'franzoesisches druecken'],
  },
  {
    name: 'Dips', nameDe: 'Dips', type: ExerciseType.BODYWEIGHT,
    primary: ['triceps'], secondary: ['chest'],
    aliases: ['dips', 'dip', 'barrendips'],
  },

  // --- Legs ----------------------------------------------------------------
  {
    name: 'Squat', nameDe: 'Kniebeuge', equipment: 'Langhantel',
    primary: ['quadriceps'], secondary: ['glutes', 'hamstrings'],
    aliases: ['kniebeuge', 'kniebeugen', 'squat', 'squats', 'back squat', 'beugen'],
  },
  {
    name: 'Leg Press', nameDe: 'Beinpresse', equipment: 'Maschine',
    primary: ['quadriceps'], secondary: ['glutes'],
    aliases: ['beinpresse', 'bein presse', 'leg press', 'legpress'],
  },
  {
    name: 'Leg Extension', nameDe: 'Beinstrecker', equipment: 'Maschine',
    primary: ['quadriceps'],
    aliases: ['beinstrecker', 'bein strecker', 'leg extension', 'leg extensions'],
  },
  {
    name: 'Leg Curl', nameDe: 'Beinbeuger', equipment: 'Maschine',
    primary: ['hamstrings'],
    aliases: ['beinbeuger', 'bein beuger', 'leg curl', 'leg curls'],
  },
  {
    name: 'Romanian Deadlift', nameDe: 'Rumänisches Kreuzheben', equipment: 'Langhantel',
    primary: ['hamstrings'], secondary: ['glutes', 'back'],
    aliases: ['rumaenisches kreuzheben', 'romanian deadlift', 'rdl', 'gestrecktes kreuzheben'],
  },
  {
    name: 'Calf Raise', nameDe: 'Wadenheben', equipment: 'Maschine',
    primary: ['calves'],
    aliases: ['wadenheben', 'waden heben', 'calf raise', 'calf raises', 'wadenmaschine'],
  },
  {
    name: 'Hip Thrust', nameDe: 'Hip Thrust', equipment: 'Langhantel',
    primary: ['glutes'], secondary: ['hamstrings'],
    aliases: ['hip thrust', 'hipthrust', 'hueftheben'],
  },
  {
    name: 'Lunge', nameDe: 'Ausfallschritt', equipment: 'Kurzhantel',
    primary: ['quadriceps'], secondary: ['glutes'],
    aliases: ['ausfallschritt', 'ausfallschritte', 'lunge', 'lunges'],
  },

  // --- Core ----------------------------------------------------------------
  {
    name: 'Crunch', nameDe: 'Crunch', type: ExerciseType.BODYWEIGHT,
    primary: ['core'],
    aliases: ['crunch', 'crunches', 'bauchpresse', 'sit up', 'situps'],
  },
  {
    name: 'Leg Raise', nameDe: 'Beinheben', type: ExerciseType.BODYWEIGHT,
    primary: ['core'],
    aliases: ['beinheben', 'bein heben', 'leg raise', 'leg raises', 'hanging leg raise'],
  },
  {
    name: 'Plank', nameDe: 'Unterarmstütz', type: ExerciseType.DURATION,
    primary: ['core'],
    aliases: ['plank', 'planks', 'unterarmstuetz', 'brett'],
  },

  // --- Cardio --------------------------------------------------------------
  {
    name: 'Treadmill', nameDe: 'Laufband', type: ExerciseType.CARDIO,
    primary: ['cardio'],
    aliases: ['laufband', 'treadmill', 'laufen', 'running', 'joggen'],
  },
  {
    name: 'Rowing Machine', nameDe: 'Rudergerät', type: ExerciseType.CARDIO,
    primary: ['cardio'],
    aliases: ['rudergeraet', 'rudermaschine', 'rowing machine', 'ergometer rudern'],
  },
  {
    name: 'Cycling', nameDe: 'Fahrradergometer', type: ExerciseType.CARDIO,
    primary: ['cardio'],
    aliases: ['fahrrad', 'fahrradergometer', 'ergometer', 'spinning', 'cycling', 'radfahren'],
  },
];

async function main(): Promise<void> {
  console.log('▶ Seed startet …');

  // --- muscle groups -------------------------------------------------------
  for (const [index, group] of MUSCLE_GROUPS.entries()) {
    await prisma.muscleGroup.upsert({
      where: { key: group.key },
      create: { ...group, parentKey: group.parentKey ?? null, sortOrder: index },
      update: { nameEn: group.nameEn, nameDe: group.nameDe, parentKey: group.parentKey ?? null, sortOrder: index },
    });
  }
  console.log(`  ✓ ${MUSCLE_GROUPS.length} Muskelgruppen`);

  const groupIds = new Map(
    (await prisma.muscleGroup.findMany({ select: { id: true, key: true } })).map((g) => [g.key, g.id]),
  );

  // --- exercises -----------------------------------------------------------
  let created = 0;
  let updated = 0;

  for (const seed of EXERCISES) {
    const existing = await prisma.exercise.findFirst({ where: { userId: null, name: seed.name } });

    const exercise = existing
      ? await prisma.exercise.update({
          where: { id: existing.id },
          data: {
            nameDe: seed.nameDe,
            type: seed.type ?? ExerciseType.STRENGTH,
            equipment: seed.equipment ?? null,
            deletedAt: null,
          },
        })
      : await prisma.exercise.create({
          data: {
            userId: null,
            name: seed.name,
            nameDe: seed.nameDe,
            type: seed.type ?? ExerciseType.STRENGTH,
            equipment: seed.equipment ?? null,
            isCustom: false,
            source: DataSource.SEED,
          },
        });

    existing ? (updated += 1) : (created += 1);

    // Muscle group links – rebuilt so seed changes take effect.
    await prisma.exerciseMuscleGroup.deleteMany({ where: { exerciseId: exercise.id } });
    const links = [
      ...seed.primary.map((key) => ({ key, role: MuscleRole.PRIMARY, contribution: 1 })),
      ...(seed.secondary ?? []).map((key) => ({ key, role: MuscleRole.SECONDARY, contribution: 0.4 })),
    ];
    for (const link of links) {
      const muscleGroupId = groupIds.get(link.key);
      if (!muscleGroupId) {
        console.warn(`  ! Unbekannte Muskelgruppe "${link.key}" bei ${seed.name}`);
        continue;
      }
      await prisma.exerciseMuscleGroup.create({
        data: { exerciseId: exercise.id, muscleGroupId, role: link.role, contribution: link.contribution },
      });
    }

    // Aliases – normalised, deduplicated, including both display names.
    const aliases = new Set<string>([
      normalizeName(seed.name),
      normalizeName(seed.nameDe),
      ...seed.aliases.map(normalizeName),
    ]);
    for (const alias of aliases) {
      if (!alias) continue;
      await prisma.exerciseAlias.upsert({
        where: { exerciseId_alias: { exerciseId: exercise.id, alias } },
        create: { exerciseId: exercise.id, alias },
        update: {},
      });
    }
  }

  console.log(`  ✓ Übungen: ${created} neu, ${updated} aktualisiert`);
  console.log('✔ Seed fertig.');
}

main()
  .catch((error) => {
    console.error('✖ Seed fehlgeschlagen:', error);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
