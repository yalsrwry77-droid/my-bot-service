import telebot
import requests
import os

# ضع هنا التوكين الخاص ببوتك (يمكنك استبدال الكود بوضعه مباشرة هنا)
TOKEN = 'ضع_التوكين_هنا'
bot = telebot.TeleBot(TOKEN)

@bot.callback_query_handler(func=lambda call: call.data == 'check')
def handle_check(call):
    # رابط نظامك الذي سيقوم بإغلاق العملية
    system_url = "https://your-yemoney-system-url.com/api/complete" 
    params = {'id': call.message.text}
    try:
        requests.get(system_url, params=params)
        bot.answer_callback_query(call.id, "تم التزويد بنجاح ✅")
    except:
        bot.answer_callback_query(call.id, "حدث خطأ")

bot.polling()
