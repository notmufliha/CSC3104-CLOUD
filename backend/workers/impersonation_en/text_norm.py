"""
text_norm.py - the impersonation_en cleaning function, under the same name every worker uses.

The real code lives in text_utils.py and must stay there: the saved model (model/model.pkl) references
text_utils.prep and text_utils.CalibratedModel by that module name, so renaming it would break loading.
The model applies this cleaning itself, inside its pipeline, which is what keeps training and serving
identical; predict.py therefore passes raw text. This wrapper is for reading and testing the cleaning
on its own.
"""
from text_utils import prep


def normalize(text, mask=True, caller=True) -> str:
    """Raw transcript -> what the model sees: caller-only turns, cleaned, brands masked by type."""
    return prep([text], mask=mask, caller=caller)[0]
