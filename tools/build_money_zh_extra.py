"""Builds backend/workers/money_zh/data/money_zh_extra.csv (read by common_zh/train_zh.py).
Run: python tools/build_money_zh_extra.py backend/workers/money_zh/data/money_zh_extra.csv

Why: v6 money_zh scored a legit bill reminder ("您的信用卡有…的款项，本月…号到期") 0.86, which with the bank's
self-introduction (impersonation 1.00) fired the aggregator's money rule -> SCAM on a normal call
(android script normal_zh_10_bank_reminder).

Label definition (same as gold_test_zh.csv): 1 = the caller asks you to pay / transfer to a destination THEY control
(personal or "safe" account, PayNow number, link, gift cards) or to hand over card details, OTP, passwords, banking
login. 0 = bills, amounts, due dates, refunds into your registered account, paying through official channels
(app, internet banking, AXS, GIRO, counter, invoice account), and anti-scam advice.
Hand-written; none copied from the android scripts, the gold set or the probes (checked by similarity).
"""
import csv
import sys

LEGIT = [
    "您这个月的信用卡账单是三百六十元，月底之前缴清就可以了。",
    "提醒您，信用卡最低还款额是五十元，到期日是下个星期三。",
    "您的账单已经出来了，应缴金额是两百八十元，可以在手机银行查看。",
    "这期账单的截止日期是二十号，过了日期可能会有逾期费。",
    "您的房贷分期下个月一号会自动扣款，请确保户口有足够余额。",
    "您的手机账单是六十八元，十五号到期，可以在应用里付。",
    "水电费一百二十元已经过期几天了，您方便的时候用AXS付就可以。",
    "您有两个月的杂费还没缴，大约一百六十元，可以来办事处柜台付。",
    "保单的年费两百三十元月底到期，可以在客户网站付款。",
    "您领药的费用还有十八元没付，下次来的时候在柜台付就可以。",
    "咨询费是八十元，看完以后在诊所付。",
    "货到付款，金额是四十二元，可以付现金或者扫单子上的二维码。",
    "房租还是星期五交，同样的金额，同样的户口。",
    "下学期学费三百二十元，可以在前台付或者转账到发票上的中心户口。",
    "修车总共三百五十元，取车的时候刷卡就可以。",
    "会员续约每个月九十九元，下次来前台办理就可以了。",
    "每个人四十元，你有空再转给我，不急。",
    "我今晚转一百五十元到你的户口，明天你查一下。",
    "到会的余款当天付给我们的员工就可以，现金或刷卡都行。",
    "这一季的管理费是九百六十元，可以用支票或者转账，户口印在通知上。",
    "薪水星期一才入账，银行资料不用改。",
    "您可以设定GIRO每个月自动缴付，这样就不会忘记。",
    "付款可以用网上银行、手机银行或者AXS机。",
    "如果您已经付了，就不用理会这个提醒，入账需要一两天。",
    "我们不会在电话里问您的卡号或者验证码。",
    "请不要把一次性密码告诉任何人，包括银行职员。",
    "银行永远不会叫您把钱转到所谓的安全户口。",
    "如果有人跟您要验证码，那一定是骗子，请马上挂断。",
    "理赔款会存入您之前登记的账户，您不需要再提供银行资料。",
    "退款会在七个工作天内退回到您原来付款的卡上。",
    "补发的提款卡这个星期会寄到，您不用到分行领取。",
    "您的卡已经冻结了，钱是安全的，不需要转移任何资金。",
    "如果付款有困难，我们可以帮您安排分期付款。",
    "逾期费是十元，下次请在到期日之前缴付。",
    "您的电费账单比上个月高，因为天气热用了比较多冷气。",
    "这笔消费是一百九十元，请问是您本人刷的吗？",
    "您只要在应用里按确认就可以，不需要告诉我任何号码。",
    "您的储蓄户口这个月的利息已经存进去了。",
    "每一笔消费的明细都列在月结单的第二页。",
    "停车费我已经付了，你不用给我。",
    "年费已经帮您免掉了，这期账单不会收。",
    "您的分期付款还剩三期，每期两百元，会自动从卡里扣。",
    "下个月起月费会调整到四十五元，详情我们会寄信给您。",
    "押金会在您退房的时候全额退还。",
    "这张发票的金额是一千二百元，三十天内付款就可以。",
    "您的信用卡这期有一笔四百多元的款项要还，下个月三号截止。",
    "提醒您一下，您的贷款这个月的供款是八百五十元，下星期五扣。",
    "这个月的账单金额比较高，是因为有一笔年费，您可以在应用里申请豁免。",
    "您上个月的款项我们已经收到了，户口没有欠款。",
    "这笔款项会在三个工作天内退回您的卡。",
    "您的保单款项已经自动扣除，不需要另外缴付。",
    "合约下个月到期，到期后会按普通月费继续。",
    "您的信用卡本期应还九百元，还款日是这个月二十八号。",
    "提醒您，您卡上有一笔一千零五十元的款项，下个星期一前还清就不会有利息。",
    "您的信用卡这个月要还的金额是一百七十元，到期前在应用里还就可以了。",
    "这张卡的款项十号到期，您之前设定了自动扣款，所以不需要做什么。",
]

SCAM = [
    "您的信用卡账单逾期了，请马上转账到这个户口，不然银行会起诉您。",
    "您的账单有问题，请把卡号和背后三位数字告诉我，我帮您处理。",
    "要避免停机，请在一个小时内通过我发的链接付款。",
    "请把还款转到我们追讨专员的户口，不要打银行热线。",
    "您的电费欠了四个月，现在就要把押金转到收款员的PayNow号码。",
    "保单今天会失效，请先把保费转到我们代理的个人户口。",
    "退款之前，请先把刚刚收到的那组六位数字读给我。",
    "请在网页上输入您的卡号和验证码来缴付处理费。",
    "为了取消这笔交易，请把六位数的验证码告诉我。",
    "调查期间您的钱不安全，请全部转去我们的暂存户口。",
    "你可以先转三千块到我朋友的户口吗？",
    "医院要先交四千块押金，你把钱转到医院代理的户口。",
    "律师等一下会给你银行资料，你今天要把五千块保释金转过去。",
    "你PayNow八百块到这个新号码，我的银行应用用不了。",
    "你直接把两千块转给房东，我现在把他的户口发给你。",
    "借我一千五百块，转到这个户口，然后把截图发给我。",
    "请提供您的网上银行用户名和密码，我们帮您领取退款。",
    "请下载这个应用程序，然后输入您的银行卡资料完成付款。",
    "你先付一笔手续费，贷款马上就可以批下来。",
    "只要先付一笔清关费，包裹今天就能领。",
    "请用礼品卡付款，然后把卡背面的号码念给我。",
    "转账完成后请把收据截图发给我，我帮您取消断电。",
    "您的账单还有一笔欠款，今天之内转到我给您的户口就不会停电。",
    "您的信用卡有一笔款项到期了，请把卡号和有效期报给我核对。",
    "这笔款项要先转到我们的监管户口，才能帮您解冻。",
]


def main(out):
    rows = []
    for label, texts in ((0, LEGIT), (1, SCAM)):
        for i, t in enumerate(texts):
            rid = f"money_extra_{'scam' if label else 'legit'}_{i:02d}"
            rows.append(dict(row_id=rid, text_raw=t, label=label, source="extra_payment_v7", conversation_id=rid,
                             scenario_id=rid, speaker="caller", label_method="hand_written",
                             hard_negative=label == 0, synthetic=True))
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    print(f"wrote {out}: {len(rows)} rows ({sum(r['label'] for r in rows)} positive)")


if __name__ == "__main__":
    main(sys.argv[1])
