"""
text_norm.py - the money_en cleaning function, under the same name every worker uses.

The real code lives in money_norm.py and must stay there: money_model.py imports it, and the saved model
(model/E_model.joblib) references money_model.MoneyModel. MoneyModel applies this cleaning itself inside
predict_proba, which is what keeps training and serving identical; predict.py therefore passes raw text.
This wrapper is for reading and testing the cleaning on its own.
"""
from money_norm import clean_text, split_sentences  # noqa: F401


def normalize(text) -> str:
    """Raw sentence -> what the model sees (placeholders, lowercase, no punctuation, contractions expanded)."""
    return clean_text(text)
