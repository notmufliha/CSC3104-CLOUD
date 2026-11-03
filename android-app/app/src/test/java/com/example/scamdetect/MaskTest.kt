package com.example.scamdetect

import com.example.scamdetect.logic.Mask
import org.junit.Assert.assertEquals
import org.junit.Test

/** Same cases as backend/review/mask.py was checked with, so the app and the server agree. */
class MaskTest {
    private fun check(input: String, expected: String) = assertEquals(expected, Mask.mask(input))

    @Test fun namesAfterTitlesAndCues() {
        check("This is Officer John Tan from the Singapore Police Force.",
            "This is Officer [PERSON_NAME] from the Singapore Police Force.")
        check("My name is Sarah, I am calling from DBS.", "My name is [PERSON_NAME], I am calling from DBS.")
        check("Inspector Wong here. Mrs Tan, please hold.", "Inspector [PERSON_NAME] here. Mrs [PERSON_NAME], please hold.")
    }

    @Test fun organisationsStay() {
        check("This is the police. This is DBS bank. I am calling about your account.",
            "This is the police. This is DBS bank. I am calling about your account.")
        check("Hello this is Singapore Customs.", "Hello this is Singapore Customs.")
    }

    @Test fun numbersMoneyContacts() {
        check("Transfer \$5,000 to account 123-456-789 now and read me the OTP 482913.",
            "Transfer [MONEY_AMOUNT] to account [PHONE_NUMBER] now and read me the OTP [NUMBER].")
        check("Call me back at +65 9123 4567 or email scam@fake.com, visit www.dbs-secure.com/login",
            "Call me back at [PHONE_NUMBER] or email [EMAIL_ADDRESS], visit [URL]")
        check("Your NRIC S1234567D has been used. Pay 300 dollars within 24 hours.",
            "Your NRIC [NUMBER] has been used. Pay [MONEY_AMOUNT] within [NUMBER] hours.")
    }

    @Test fun chinese() {
        check("我是王警官，请转账5000元。我叫李明", "我是王警官，请转账[MONEY_AMOUNT]。我叫[PERSON_NAME]")
    }
}
