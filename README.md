Gmail Rescue — Smart Inbox Intelligence & Life Story Generator
Turn your noisy Gmail inbox into meaningful insights using powerful AI agents.

______________________________________________________________________________________

⭐ Overview
Gmail Rescue is a multi-agent Gmail assistant that summarizes inbox activity, cleans up email clutter, and even reconstructs a personal life story from your emails — powered by Gemini and function-calling agents.
This project demonstrates a complete agent ecosystem built using:
•	Agent routing
•	Multi-step agent workflows
•	Strict function-calling
•	Gmail automation tools

🧠 1. The Pitch (Problem, Solution, Value)
Problem
People receive hundreds of emails every week — promotions, newsletters, receipts, family notes, random subscriptions — and important information gets buried. Organizing inboxes manually is frustrating and time-consuming.
Solution
Gmail Rescue provides:
•	🔍 Smart email search & summarization
•	🗑️ Inbox cleanup automation (archive, trash, mark-as-read, unsubscribe)
•	📬 Promotions vs. personal message insights
•	📖 “Life Story from Gmail” — reconstruct events from your emails
The system uses multiple intelligent agents to route requests, analyze messages, decide actions, and execute Gmail operations reliably through Java-based function tools.
Value
•	Saves time
•	Reduces email anxiety
•	Turns years of emails into meaningful reflections
•	Provides safe, user-confirmed Gmail operations
•	Demonstrates real-world multi-agent design

🏗️ 2. Core Innovation & Agent Value
This project purposely centers agents as the backbone of the entire system.
Agents Implemented
Agent           	Role
RouterAgent	     Understands user intent and delegates to subagents
SummaryAgent	   Produces summaries of unread or promotional emails
CleanupAgent	   Decides actions: archive, trash, mark-as-read
LifeStoryAgent	 Fetches emails and writes a personal narrative
ActorAgent	     Executes Gmail tool operations via Java functions

✔ Key AI Course Concepts Demonstrated
Concept	Included?	Where
Agent Routing	✅	RouterAgent (intent classification)
Orchestration	✅	Summary → Decider → Actor
Function Calling	✅	ManageInbox.java with FunctionTool
Structured Outputs	✅	Decider emits strict JSON
Tool Enforcement	✅	LifeStoryAgent MUST call a tool first

🧩 3. Architecture
High-Level Flow
User Request
   ↓
RouterAgent (intent → route)
   ↓
SummaryAgent / CleanupAgent / LifeStoryAgent
   ↓
Analyzer (searchEmails / getEmail / getThread)
   ↓
Decider (JSON actions)
   ↓
ActorAgent (executes Gmail tools)
   ↓
Response back to user
Tools (Java Gmail Functions)
Located in:
/mnt/data/ManageInbox.java
Tools include:
•	searchEmails
•	getEmail
•	getThread
•	analyzeEmailBatch
•	trashEmail
•	archiveEmail
•	markAsRead
•	unsubscribeEmail
All tools return Map<String,Object> or List<Map<>> to avoid JSON deserialization errors.

⚙️ 4. Installation & Setup
Prerequisites
•	Java 21+
•	Maven or Gradle
•	Google Cloud OAuth Credentials (not included)
Run
mvn clean package
java -jar target/gmail-support-app.jar
Open Dev UI:
👉 http://localhost:8080/

Test Commands:
•	“Clean my inbox”
•	“Summarize my promotions”
•	“Mark these as read”
•	“Unsubscribe newsletters”
•	“Tell me my life story from Gmail”

📁 5. File Overview (Local Uploaded Files)
These are the files you uploaded during development:
•	/mnt/data/GmailSupportApp.java
•	/mnt/data/GmailAgents.java
•	/mnt/data/ManageInbox.java
(You will copy these into your repo in the appropriate places.)

🧪 6. Testing
Manual tests done:
•	Search + summarization works on unread/promotional mail
•	Archive/trash/mark-as-read execute via ActorAgent
•	LifeStoryAgent performs: search → fetch → compile story
•	Deserialization issues resolved by returning Java Maps (not JSON strings)

🚀 7. Deployment (Cloud Run)
Dockerfile included below.
Build Docker Image
docker build -t gmail-rescue .
Run Locally
docker run -p 8080:8080 gmail-rescue
Deploy to Cloud Run
gcloud run deploy gmail-rescue \
  --source . \
  --region us-central1 \
  --platform managed \
  --allow-unauthenticated



