# Think to Buy
### The app that tells you if you can actually afford it

**Live at: [himothyjimothy17-tech.github.io/Think-To-Buy](https://himothyjimothy17-tech.github.io/Think-To-Buy)**

> Built by an 8th grader who got tired of seeing people make bad financial decisions.

---

## What is this?

Most people buy things without actually thinking about whether they can afford it. Think to Buy fixes that.

Type something like *"thinking of buying AirPods for $249"* and instantly get a green, yellow, or red verdict — with a clear explanation of why, where to buy it cheapest, and what the opportunity cost is.

It also teaches you the financial concepts school never covers — taxes, investing, compound interest, how mortgages work — with real examples and quizzes.

---

## Features

**Purchase Analyzer**
- Natural language input — just describe what you want to buy
- Voice input support
- Green / yellow / red verdict with clear reasoning
- Savings percentage, weeks to recover, emergency runway
- Auto-saves every decision to your history

**Compare Mode**
- Side-by-side product comparison
- Real specs powered by AI (noise cancellation, battery life, materials)
- Nutritional data from Open Food Facts for food items
- Product summaries from Wikipedia
- Secondary recommendation showing what the other option is better at

**Spending Dashboard**
- Tracks money saved by saying no
- 50/30/20 rule comparison against your actual spending
- Smart vs risky vs avoided breakdown with charts
- CSV export

**Financial Education**
8 lessons on things school never taught you — taxes, compound interest, investing, credit scores, how business works, inflation, budgeting, and retirement. Each has a real-world example and a quiz.

**AI Advisor**
- Rules-based for common questions (instant, no API call)
- Groq + Llama 3.3 70B for complex questions
- Remembers conversation context
- Personalised to your actual numbers

**Markets**
- Live charts for stocks and crypto
- Plain English analysis for major assets
- Set your own Buy / Neutral / Don't Buy verdict per asset

**Goals and Bills**
- Savings goals with progress tracking and weekly timeline
- Bill tracker with disposable income calculation
- Spending streak tracker

**Other**
- Shareable verdict cards — download as image or copy as text
- Currency auto-detection for 30+ countries
- Cloud sync via Supabase
- Install to home screen (PWA)
- Light and dark mode

---

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | Vanilla HTML, CSS, JavaScript |
| Auth and Database | Supabase |
| AI Advisor | Groq API (Llama 3.3 70B) |
| Charts | Chart.js, TradingView |
| Food Data | Open Food Facts API |
| Product Info | Wikipedia API |
| Hosting | Netlify |

The entire app is a single HTML file. No framework, no build step, no backend server.

---

## Why I Built This

I kept noticing that people make spending decisions without thinking them through. Buy something, regret it, repeat.

At the same time nobody actually teaches young people how money works — not properly. The gap between what people know about personal finance and what they should know is huge.

So I built something that does both — helps you make smarter spending decisions and teaches you the concepts behind them.

---

## Running Locally

No installation needed. Just open the file in a browser.

```
git clone https://github.com/himothyjimothy17-tech/Think-To-Buy
cd Think-To-Buy
open should-i-buy-it.html
```

Note: Auth and cloud sync require a Supabase project. The live version at thinktobuy.netlify.app is fully configured.

---

## What I Learned

- Shipping a real product is harder than building one
- Product decisions are harder than technical ones
- Users find bugs you never imagined
- A single HTML file can do a lot more than people think
- Financial literacy is a much bigger problem than I realised

---

## Roadmap

- Push notifications for goal reminders
- Weekly spending digest
- More regional store suggestions
- Native mobile app

---

## About

Built by a middle school student interested in fintech and making financial literacy more accessible.

**Live at: [himothyjimothy17-tech.github.io/Think-To-Buy](https://himothyjimothy17-tech.github.io/Think-To-Buy)**

---

*If you find this useful, give it a star — it means a lot to an 8th grade builder.*
