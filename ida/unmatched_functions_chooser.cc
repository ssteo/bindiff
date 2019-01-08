#include "third_party/zynamics/bindiff/ida/unmatched_functions_chooser.h"

#include <cstring>
#include <vector>

#include "third_party/absl/strings/str_cat.h"
#include "third_party/zynamics/bindiff/ida/ui.h"
#include "third_party/zynamics/binexport/util/format.h"

namespace security {

using binexport::FormatAddress;

namespace bindiff {

constexpr const int UnmatchedFunctionsChooserBase::kColumnWidths[];
constexpr const char* const UnmatchedFunctionsChooserBase::kColumnNames[];

const void* UnmatchedFunctionsChooserBase::get_obj_id(size_t* len) const {
  *len = title_.size();
  return title_.c_str();
}

void UnmatchedFunctionsChooserBase::get_row(qstrvec_t* cols, int* /* icon_ */,
                                            chooser_item_attrs_t* /* attrs */,
                                            size_t n) const {
  if (!results_) {
    return;
  }
  Results::UnmatchedDescription desc = GetDescription(n);

  (*cols)[0] = FormatAddress(desc.address).c_str();
  (*cols)[1] = desc.name.c_str();
  (*cols)[2] = std::to_string(desc.basic_block_count).c_str();
  (*cols)[3] = std::to_string(desc.instruction_count).c_str();
  (*cols)[4] = std::to_string(desc.edge_count).c_str();
}

size_t UnmatchedFunctionsChooserPrimary::get_count() const {
  return results_ ? results_->GetNumUnmatchedPrimary() : 0;
}

Results::UnmatchedDescription UnmatchedFunctionsChooserPrimary::GetDescription(
    size_t index) const {
  return results_->GetUnmatchedDescriptionPrimary(index);
}

size_t UnmatchedFunctionsChooserSecondary::get_count() const {
  return results_ ? results_->GetNumUnmatchedSecondary() : 0;
}

Results::UnmatchedDescription
UnmatchedFunctionsChooserSecondary::GetDescription(size_t index) const {
  return results_->GetUnmatchedDescriptionSecondary(index);
}

constexpr const char UnmatchedFunctionsChooserPrimary::kTitle[];
constexpr const char UnmatchedFunctionsChooserSecondary::kTitle[];

}  // namespace bindiff
}  // namespace security
