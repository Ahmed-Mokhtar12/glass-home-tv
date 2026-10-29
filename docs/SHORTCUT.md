# The iPhone Shortcut "TV": step by step

Siri says **"Tell me"**, sends what you say to the TV, reads the TV's answer, and listens again, until you say "thank you".
About 15 minutes. Do one step, then check it looks like the description.

**You need:**
- the TV's IP address (`<TV-IP>`)
- the **code** from the TV's Voice control screen (the microphone tile in the Glass Home dock)
- the OpenRouter key saved on that screen (see README, step 5)

## Build it (iPhone, Shortcuts app)
Tap **+** to start a new Shortcut. Tap its name at the top → **Rename** → `TV`.

**Step 1: the first prompt**
1. Search `Text` and add **Text**. Type: `Tell me`
2. Search `Set Variable` and add **Set Variable**. Tap **Variable Name** and type `Prompt`. Its input should already be **Text**.

**Step 2: the loop**
3. Search `Repeat` and add **Repeat**. Set it to **20** times.
   Everything below goes **inside** the Repeat, between **Repeat** and **End Repeat**.

**Step 3: listen**
4. Add **Ask for Input** inside the Repeat.
   - Type: **Text**.
   - Prompt: tap **Prompt** → **Select Variable** → choose **Prompt**, the variable from step 1.
   - Default Answer: leave it empty.

**Step 4: send to the TV**
5. Add **Get Contents of URL** under Ask for Input.
   - URL: `http://<TV-IP>:8765/talk`
   - Method: **POST**
   - Headers: Key `X-Token`, Text = **your code**
   - Request Body: **File**. Tap File → **Select Variable** → **Provided Input**.

**Step 5: read the answer**
6. Add **Get Dictionary Value**: **Get Value for** `say` **in** Contents of URL.
7. Add **Set Variable**: name `Prompt`, input = **Dictionary Value**.
8. Add **Text** and put **Prompt** in it: tap inside, then choose **Prompt** above the keyboard. This turns the answer into text, so the If below offers "contains".

**Step 6: stop when the conversation is over**
9. Add **If**:
   - Input: the **Text** from item 8.
   - Condition: **contains**.
   - Value: `Goodbye`.

   Every answer that ends the conversation contains "Goodbye": after "thank you", "bye", "that's all", or turning the TV off. The TV also returns an `end` field, but on some iOS versions an If on a dictionary value offers only "has any value", so the word check is more reliable.
10. Inside the If (between If and Otherwise), add **Speak Text**. Text = the **Text** from item 8.
11. Under it, still inside the If, add **Stop This Shortcut**.

**Step 7: after the loop**
12. Below **End Repeat**, add **Speak Text** with **Prompt**.

### The finished Shortcut
```
Text "Tell me"
Set variable Prompt to Text
Repeat 20 times
    Ask for Text with Prompt
    Get contents of http://<TV-IP>:8765/talk   (POST, header X-Token = <code>, body File = Provided Input)
    Get Value for "say" in Contents of URL
    Set variable Prompt to Dictionary Value
    Text (Prompt)
    If Text contains "Goodbye"
        Speak Text
        Stop this shortcut
    Otherwise
    End If
End Repeat
Speak Prompt
```

## Test it
1. Say **"Hey Siri, TV"**. Siri says "Tell me". Say "turn the volume up a little".
2. Siri answers and listens again. Watch the top of the TV: a small banner shows what it heard.
3. Try "open YouTube and search for cooking videos", then "play the first one", then "thank you". It should stop.
4. The first time, iOS asks about connecting to `<TV-IP>`. Choose **Always Allow**.

**During a conversation, don't say "Siri".** Siri then takes the request itself and the Shortcut ends.

## What you can say
| You want | Say |
|---|---|
| YouTube | "play relaxing piano music", "search for cooking videos", "open my library", "scroll down", "play the second video" |
| Arabic on YouTube | "search YouTube for Amr Diab songs", "play cooking videos in Arabic". Arabic names and "in Arabic" are searched in Arabic letters. |
| While watching | "forward 30 seconds", "back 10 seconds", "pause", "volume up a little", "mute" |
| Several things at once | "open YouTube, search for football and play the first one" |
| Follow-ups | "the next one", "go back", "again" |
| TV | "turn off the TV", "turn on the TV" (on works only while the TV still has network) |
| Finish | "thank you", "bye", "that's all" |

## If something is wrong
| You hear | Fix |
|---|---|
| "Wrong code." | `X-Token` in step 4 must match the code on the Voice control screen. |
| "I need the AI key first…" | Save the OpenRouter key on the Voice control screen. Simple commands like volume still work without it. |
| Siri reads `{"say": …}` | Step 5 must use Get Dictionary Value, and Prompt must be set from Dictionary Value. |
| It never stops | Step 6 must be **If Text contains Goodbye**, with Stop inside the If. |
| "Something went wrong" | The iPhone can't reach the TV. Is the TV on? Is the iPhone on the same Wi-Fi? Did the TV's IP change? |
| "I'm still busy with the last request" | A long job is running. Wait a few seconds, or say "cancel". |
## Optional: let Siri listen longer
**The problem:** "Ask for Input" stops listening after a short pause, about 4–5 seconds.

**The fix:** "Dictate Text" has a **Stop Listening** option:
- **After Pause** waits longer for you to start and to finish.
- **On Tap** listens until you tap, so it isn't hands-free.

iOS has no setting for an exact 10 seconds.

Change only the inside of the Repeat. Keep a copy first: long-press the Shortcut, then **Duplicate**.
1. **Remove "Ask for Input":** in the Repeat, tap the ✕ on **Ask for Input**.
2. **Add Speak Text:** in the same place, add **Speak Text**, with Text = the **Prompt** variable. Siri says "Tell me" or the TV's answer.
3. **Add Dictate Text:** under it, add **Dictate Text**. Tap the arrow to show more:
   - **Language:** the same English as your Siri, for example English (United States).
   - **Stop Listening:** **After Pause**.
4. **Point the TV request at it:** in **Get Contents of URL** → Request Body → File, tap **Provided Input** → **Select Variable** → **Dictated Text**.
5. **Leave the rest as it is:** Get Dictionary Value, Set Variable, Text, If… Goodbye, and Speak after End Repeat.

The loop now looks like this:
```
Repeat 20 times
    Speak Text (Prompt)
    Dictate Text (After Pause)
    Get contents of http://<TV-IP>:8765/talk   (POST, X-Token, File = Dictated Text)
    Get Value for "say" in Contents of URL
    Set variable Prompt to Dictionary Value
    Text (Prompt)
    If Text contains "Goodbye"
        Speak Text
        Stop this shortcut
    Otherwise
    End If
End Repeat
Speak Prompt
```
**Test it:** say "Hey Siri, TV", wait 3 seconds, then speak slowly.

**If it doesn't work** (for example on a locked iPhone): use the duplicate you kept and delete the changed one.

**If Siri mishears you:** that's fine most of the time. Since 2.7 the TV reads Siri's words by how they sound: "you too" means YouTube, "lions" means Lionz TV, "a mere diab" means Amr Diab, and "paws" means pause.
