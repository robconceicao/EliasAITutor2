"""Parse Fluencia doc raw text into elias_curriculum_seed.json"""
from pathlib import Path
import re
import json

ROOT = Path(__file__).resolve().parent.parent
text = (ROOT / "_curriculum_raw.txt").read_text(encoding="utf-8")
start = text.find("FASE 1 - FUNDAÇÃO (A1 → A2)\nSemanas 1 a 6")
assert start > 0, "Could not find curriculum body"
body = text[start:]

week_headers = list(re.finditer(r"^Semana (\d+):\s*(.+)$", body, re.M))
print("Found week headers:", len(week_headers))


def extract_between(s, start_pat, end_pats):
    m = re.search(start_pat, s, re.I | re.M)
    if not m:
        return ""
    rest = s[m.end() :]
    end = len(rest)
    for ep in end_pats:
        em = re.search(ep, rest, re.I | re.M)
        if em and em.start() < end:
            end = em.start()
    return rest[:end].strip()


def phase_for(week):
    if week <= 6:
        return 1
    if week <= 13:
        return 2
    if week <= 20:
        return 3
    return 4


city_map = {
    1: "New York",
    2: "Chicago",
    3: "Los Angeles",
    4: "Houston",
    5: "Phoenix",
    6: "Philadelphia",
    7: "San Antonio",
    8: "San Diego",
    9: "Dallas",
    10: "San Jose",
    11: "Austin",
    12: "Jacksonville",
    13: "Fort Worth",
    14: "Columbus",
    15: "Charlotte",
    16: "Indianapolis",
    17: "San Francisco",
    18: "Seattle",
    19: "Denver",
    20: "Washington",
    21: "Boston",
    22: "Nashville",
    23: "Portland",
    24: "Las Vegas",
    25: "Miami",
    26: "Atlanta",
}

weeks = []
for i, h in enumerate(week_headers):
    week_num = int(h.group(1))
    title = h.group(2).strip()
    start_pos = h.end()
    end_pos = week_headers[i + 1].start() if i + 1 < len(week_headers) else len(body)
    section = body[start_pos:end_pos]

    meta = re.search(
        r"Período:.*?Nível:\s*([A-C][12]).*?Campo lexical:\s*(.+)", section
    )
    level = meta.group(1) if meta else ("A1" if week_num <= 6 else "B1")
    lexis = meta.group(2).strip() if meta else title

    obj_block = extract_between(
        section,
        r"Objetivos da Semana\n(?:Ao final desta semana, você será capaz de:\n)?",
        [r"^Fundamentação Teórica", r"^Chunks de Conversação"],
    )
    objectives = [
        ln.strip("•- \t")
        for ln in obj_block.splitlines()
        if ln.strip() and not ln.strip().startswith("Ao final")
    ]
    objectives = [o for o in objectives if len(o) > 10][:8]

    feynman = re.search(
        r"Ensine para dominar:.*?tópico desta semana \(([^)]+)\)", section, re.S
    )
    grammar = feynman.group(1).strip() if feynman else title

    chunks_block = extract_between(
        section,
        r"Chunks de Conversação",
        [r"^Exercícios com Gabarito", r"^Aplicação da Técnica"],
    )
    chunks = []
    lines = [ln.strip() for ln in chunks_block.splitlines() if ln.strip()]
    i_line = 0
    while i_line < len(lines) and "Expressão" not in lines[i_line] and not (
        i_line + 1 < len(lines) and lines[i_line + 1].startswith("/")
    ):
        i_line += 1
    if i_line < len(lines) and "Expressão" in lines[i_line]:
        i_line += 1
        while i_line < len(lines) and lines[i_line] in (
            "Pronúncia (IPA)",
            "Significado / Uso",
            "Pronúncia",
            "Significado",
        ):
            i_line += 1

    while i_line < len(lines) - 1:
        en = lines[i_line]
        if en in ("Expressão", "Pronúncia (IPA)", "Significado / Uso") or en.startswith(
            "Estes são"
        ):
            i_line += 1
            continue
        if i_line + 1 < len(lines) and lines[i_line + 1].startswith("/"):
            ipa = lines[i_line + 1]
            pt_use = lines[i_line + 2] if i_line + 2 < len(lines) else ""
            if " — " in pt_use:
                pt, use = pt_use.split(" — ", 1)
            elif " - " in pt_use:
                pt, use = pt_use.split(" - ", 1)
            else:
                pt, use = pt_use, ""
            chunks.append(
                {"en": en, "ipa": ipa, "pt": pt.strip(), "use": use.strip()}
            )
            i_line += 3
        else:
            i_line += 1
        if len(chunks) >= 10:
            break

    anki_block = extract_between(
        section,
        r"Frases sugeridas para o baralho Anki",
        [r"^Prática de Conversação", r"^Marco de Progresso"],
    )
    anki = []
    for ln in anki_block.splitlines():
        ln = ln.strip()
        if not ln or ln.startswith("Crie cards"):
            continue
        m = re.match(r"^(.+?)\s*\((.+)\)\s*$", ln)
        if m:
            anki.append(m.group(1).strip())
        elif len(ln) > 5 and not ln.startswith("Anki"):
            anki.append(ln)
    anki = anki[:8]

    prompt_block = extract_between(
        section,
        r"Prompt pronto \(copie e cole na IA\)",
        [r"^Marco de Progresso", r"^A Verdade da Semana", r"^Quiz da Semana"],
    )
    conversation_prompt = " ".join(prompt_block.split())
    city_m = re.search(r"from ([A-Z][a-zA-Z\s]+?)\.", conversation_prompt)
    persona_city = city_m.group(1).strip() if city_m else city_map.get(week_num, "New York")

    weeks.append(
        {
            "week": week_num,
            "phase": phase_for(week_num),
            "level": level,
            "title": title,
            "grammar": grammar,
            "lexis": lexis,
            "persona_city": persona_city,
            "conversation_prompt": conversation_prompt,
            "objectives": objectives,
            "chunks": chunks,
            "anki_sentences": anki,
        }
    )

print("Total weeks:", len(weeks))
for w in weeks:
    print(
        f"W{w['week']:02d} phase={w['phase']} level={w['level']} "
        f"chunks={len(w['chunks'])} anki={len(w['anki_sentences'])} "
        f"prompt_len={len(w['conversation_prompt'])} title={w['title'][:45]}"
    )

missing = set(range(1, 27)) - {w["week"] for w in weeks}
print("Missing:", missing)

# Fallback: ensure 10 chunks and non-empty prompt for any weak weeks
DEFAULT_CHUNKS = [
    ("How are you?", "/haʊ ɑːr juː/", "Como vai?", "Saudação cotidiana"),
    ("Nice to meet you.", "/naɪs tə ˈmiːt juː/", "Prazer em conhecer.", "Primeiro encontro"),
    ("What do you think?", "/wʌt duː juː θɪŋk/", "O que você acha?", "Pedir opinião"),
    ("I agree with you.", "/aɪ əˈɡriː wɪð juː/", "Concordo com você.", "Concordar"),
    ("Can you help me?", "/kæn juː hɛlp miː/", "Pode me ajudar?", "Pedir ajuda"),
    ("That makes sense.", "/ðæt meɪks sɛns/", "Faz sentido.", "Confirmar entendimento"),
    ("I'm not sure.", "/aɪm nɑːt ʃʊr/", "Não tenho certeza.", "Expressar dúvida"),
    ("Let me think.", "/lɛt miː θɪŋk/", "Deixa eu pensar.", "Ganhar tempo"),
    ("Could you repeat that?", "/kʊd juː rɪˈpiːt ðæt/", "Pode repetir?", "Pedir repetição"),
    ("Thanks a lot.", "/θæŋks ə lɑːt/", "Muito obrigado.", "Agradecer"),
]

for w in weeks:
    if len(w["chunks"]) < 10:
        existing = {c["en"] for c in w["chunks"]}
        for en, ipa, pt, use in DEFAULT_CHUNKS:
            if en not in existing:
                w["chunks"].append({"en": en, "ipa": ipa, "pt": pt, "use": use})
            if len(w["chunks"]) >= 10:
                break
    if not w["conversation_prompt"]:
        w["conversation_prompt"] = (
            f'You are a friendly American native speaker from {w["persona_city"]}. '
            f'Let\'s have a 30-minute conversation about "{w["lexis"]}". '
            f'Use only vocabulary appropriate for level {w["level"]}. '
            f"Correct my mistakes naturally during the conversation, without interrupting the flow. "
            f"Start by asking me a simple question about {w['lexis']}."
        )
    if not w["anki_sentences"]:
        w["anki_sentences"] = [c["en"] for c in w["chunks"][:8]]
    if not w["objectives"]:
        w["objectives"] = [
            f"Praticar {w['grammar']} em conversação.",
            f"Usar vocabulário de: {w['lexis']}.",
            "Completar 30 minutos diários de conversa com a IA.",
        ]

out = ROOT / "seeds" / "elias_curriculum_seed.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(weeks, ensure_ascii=False, indent=2), encoding="utf-8")
print("Wrote", out, "size", out.stat().st_size)

# Re-validate chunk counts
weak = [w["week"] for w in weeks if len(w["chunks"]) < 10 or not w["conversation_prompt"]]
print("Weak after fix:", weak)
