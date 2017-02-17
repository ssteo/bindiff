// Command-line version of BinDiff.

#include <inttypes.h>
#include <signal.h>

#include <cassert>
#include <fstream>
#include <functional>
#include <iomanip>
#include <iostream>  // NOLINT
#include <memory>
#include <mutex>    // NOLINT
#include <sstream>  // NOLINT
#include <string>
#include <thread>  // NOLINT
#include <utility>
#include <vector>

#ifdef GOOGLE
#include "base/commandlineflags.h"
#include "base/init_google.h"
#include "third_party/absl/strings/case.h"

using strings::ToUpper;
#else
#include <gflags/gflags.h>
#include "strings/strutil.h"

using google::ParseCommandLineFlags;
using google::SET_FLAGS_DEFAULT;
using google::SetCommandLineOptionWithMode;
using google::SetUsageMessage;
using google::ShowUsageWithFlags;
#endif  // GOOGLE
#include "base/logging.h"
#include "base/stringprintf.h"
#include "third_party/zynamics/bindiff/call_graph.h"
#include "third_party/zynamics/bindiff/call_graph_matching.h"
#include "third_party/zynamics/bindiff/database_writer.h"
#include "third_party/zynamics/bindiff/differ.h"
#include "third_party/zynamics/bindiff/flow_graph.h"
#include "third_party/zynamics/bindiff/flow_graph_matching.h"
#include "third_party/zynamics/bindiff/log_writer.h"
#include "third_party/zynamics/bindiff/matching.h"
#include "third_party/zynamics/bindiff/xmlconfig.h"
#include "third_party/zynamics/binexport/binexport2.pb.h"
#include "third_party/zynamics/binexport/filesystem_util.h"
#include "third_party/zynamics/binexport/hex_codec.h"
#include "third_party/zynamics/binexport/timer.h"

// Note: We cannot use new-style flags here because third-party gflags does not
//       support the new syntax yet.
DEFINE_string(primary, "", "Primary input file or path in batch mode");
DEFINE_string(secondary, "", "Secondary input file (optional)");
DEFINE_string(output_dir, "", "Output path, defaults to current directory");
DEFINE_bool(log_format, false, "Write results in log file format");
DEFINE_bool(bin_format, false,
            "Write results in binary file format that can be loaded by the "
            "BinDiff IDA plugin or the GUI");
DEFINE_bool(md_index, false, "Dump MD indices (will not diff anything)");
DEFINE_bool(export, false,
            "Batch export .idb files from input directory to BinExport format");
DEFINE_bool(ls, false,
            "List hash/filenames for all .BinExport files in input directory");
DEFINE_string(config, "", "Specify config file name");

static const char kBinExportVersion[] = "9";  // Exporter version to use.

std::mutex g_queue_mutex;
volatile bool g_wants_to_quit = false;

typedef std::list<std::pair<std::string, std::string>> TFiles;
typedef std::set<std::string> TUniqueFiles;

// This function will try and create a fully specified filename no longer than
// 250 characters. It'll truncate part1 and part2, leaving all other fragments
// as is. If it is not possible to get a short enough name it'll throw an
// exception.
std::string GetTruncatedFilename(
    const std::string& path /* Must include trailing slash */,
    const std::string& part1 /* Potentially truncated */,
    const std::string& middle,
    const std::string& part2 /* Potentially truncated */,
    const std::string& extension) {
  enum { kMaxFilename = 250 };

  const std::string::size_type length = path.size() + part1.size() +
                                        middle.size() + part2.size() +
                                        extension.size();
  if (length <= kMaxFilename) {
    return path + part1 + middle + part2 + extension;
  }

  std::string::size_type overflow = length - kMaxFilename;

  // First, shorten the longer of the two strings.
  std::string one(part1);
  std::string two(part2);
  if (part1.size() > part2.size()) {
    one = part1.substr(
        0, std::max(part2.size(),
                    part1.size() > overflow ? part1.size() - overflow : 0));
    overflow -= part1.size() - one.size();
  } else if (part2.size() > part1.size()) {
    two = part2.substr(
        0, std::max(part1.size(),
                    part2.size() > overflow ? part2.size() - overflow : 0));
    overflow -= part2.size() - two.size();
  }
  if (!overflow) {
    return path + one + middle + two + extension;
  }

  // Second, if that still wasn't enough, shorten both strings equally.
  assert(one.size() == two.size());
  if (overflow / 2 >= one.size()) {
    throw std::runtime_error(
        ("Cannot create a valid filename, please choose shorter input names "
         "or directories! '" +
         path + part1 + middle + part2 + extension + "'").c_str());
  }
  return path + part1.substr(0, one.size() - overflow / 2) + middle +
         part2.substr(0, two.size() - overflow / 2) + extension;
}

class DifferThread {
 public:
  explicit DifferThread(const std::string& path, const std::string& out_path,
                        TFiles* files);  // Not owned.
  void operator()();

 private:
  TFiles* file_queue_;
  std::string path_;
  std::string out_path_;
};

DifferThread::DifferThread(const std::string& path, const std::string& out_path,
                           TFiles* files)
    : file_queue_(files), path_(path), out_path_(out_path) {}

void DifferThread::operator()() {
  const MatchingSteps default_callgraph_steps(GetDefaultMatchingSteps());
  const MatchingStepsFlowGraph default_basicblock_steps(
      GetDefaultMatchingStepsBasicBlock());

  Instruction::Cache instruction_cache;
  FlowGraphs flow_graphs1;
  FlowGraphs flow_graphs2;
  CallGraph call_graph1;
  CallGraph call_graph2;
  std::string last_file1;
  std::string last_file2;
  ScopedCleanup cleanup(&flow_graphs1, &flow_graphs2, &instruction_cache);
  do {
    std::string file1;
    std::string file2;
    try {
      Timer<> timer;
      {
        // Pop pair from todo queue.
        std::lock_guard<std::mutex> lock(g_queue_mutex);
        if (file_queue_->empty()) {
          break;
        }
        file1 = file_queue_->front().first;
        file2 = file_queue_->front().second;
        file_queue_->pop_front();
      }

      // We need to keep the cache around if one file stays the same
      if (last_file1 != file1 && last_file2 != file2) {
        instruction_cache.Clear();
      }

      // Perform setup and diff.
      // TODO(soerenme): Consider inverted pairs as well, i.e. file1 ==
      //                 last_file2.
      if (last_file1 != file1) {
        LOG(INFO) << "reading " << file1;
        DeleteFlowGraphs(&flow_graphs1);
        FlowGraphInfos infos;
        Read(JoinPath(path_, file1 + ".BinExport"), &call_graph1,
             &flow_graphs1, &infos, &instruction_cache);
      } else {
        ResetMatches(&flow_graphs1);
      }

      if (last_file2 != file2) {
        LOG(INFO) << "reading " << file2;
        DeleteFlowGraphs(&flow_graphs2);
        FlowGraphInfos infos;
        Read(path_ + "/" + file2 + ".BinExport", &call_graph2, &flow_graphs2,
             &infos, &instruction_cache);
      } else {
        ResetMatches(&flow_graphs2);
      }

      LOG(INFO) << "diffing " << file1 << " vs " << file2;

      FixedPoints fixed_points;
      MatchingContext context(call_graph1, call_graph2, flow_graphs1,
                              flow_graphs2, fixed_points);
      Diff(&context, default_callgraph_steps, default_basicblock_steps);

      Histogram histogram;
      Counts counts;
      GetCountsAndHistogram(flow_graphs1, flow_graphs2, fixed_points,
                            &histogram, &counts);
      const double similarity =
          GetSimilarityScore(call_graph1, call_graph2, histogram, counts);
      Confidences confidences;
      const double confidence = GetConfidence(histogram, &confidences);

      LOG(INFO) << "writing results";
      {
        ChainWriter writer;
        if (FLAGS_log_format) {
          writer.Add(std::make_shared<ResultsLogWriter>(GetTruncatedFilename(
              out_path_ + "/", call_graph1.GetFilename(), "_vs_",
              call_graph2.GetFilename(), ".results")));
        }
        if (FLAGS_bin_format || writer.IsEmpty()) {
          writer.Add(std::make_shared<DatabaseWriter>(GetTruncatedFilename(
              out_path_ + "/", call_graph1.GetFilename(), "_vs_",
              call_graph2.GetFilename(), ".BinDiff")));
        }

        writer.Write(call_graph1, call_graph2, flow_graphs1, flow_graphs2,
                     fixed_points);

        LOG(INFO) << StringPrintf(
            "%s vs %s ( %.3f sec ) :\tsimilarity:\t%fconfidence:\t%f",
            file1.c_str(), file2.c_str(), timer.elapsed(), similarity,
            confidence);
        for (Counts::const_iterator i = counts.begin(), end = counts.end();
             i != end; ++i) {
          LOG(INFO) << "\n\t" << i->first << ":\t" << i->second;
        }
      }

      last_file1 = file1;
      last_file2 = file2;
    } catch (const std::bad_alloc&) {
      LOG(INFO) << "Out of memory diffing " << file1 << " vs " << file2;
      last_file1.clear();
      last_file2.clear();
    } catch (const std::exception& error) {
      LOG(INFO) << file1 << " vs " << file2 << " : " << error.what();

      last_file1.clear();
      last_file2.clear();
    }
  } while (!g_wants_to_quit);
}

class ExporterThread {
 public:
  explicit ExporterThread(const std::string& in_path,
                          const std::string& out_path,
                          const std::string& ida_dir,
                          const std::string& ida_exe,
                          const std::string& ida_exe64, TUniqueFiles* files)
      : files_(files),
        in_path_(in_path),
        out_path_(out_path),
        ida_dir_(ida_dir),
        ida_exe_(ida_exe),
        ida_exe64_(ida_exe64) {}

  void operator()();

 private:
  TUniqueFiles* files_;
  std::string in_path_;
  std::string out_path_;
  std::string ida_dir_;
  std::string ida_exe_;
  std::string ida_exe64_;
};

void ExporterThread::operator()() {
  // TODO(cblichmann): Do we want to keep the export functionality in the
  //                   command-line differ?
  do {
    Timer<> timer;
    std::string file;
    {
      std::lock_guard<std::mutex> lock(g_queue_mutex);
      if (files_->empty()) {
        return;
      }
      file = *files_->begin();
      files_->erase(files_->begin());
    }

    // TODO(cblichmann): Bug: What if we have the same basename but as .idb
    //                   _and_ .i64?
    bool ida64 = false;
    auto in_file(JoinPath(in_path_, file + ".idb"));
    if (!FileExists(in_file)) {
      in_file = JoinPath(in_path_, file + ".i64");
      if (!FileExists(in_file)) {
        LOG(INFO) << "\"" << in_file << "\" not found";
        continue;
      }
      ida64 = true;
    }

    // TODO(cblichmann): Bug: If outpath is a relative path like "." IDA won't
    //                   work. We need to fully expand it first.
    std::string status_message;
    std::vector<std::string> args;
    args.push_back(JoinPath(ida_dir_, !ida64 ? ida_exe_ : ida_exe64_));
    args.push_back("-A");
    args.push_back("-OExporterModule:" + out_path_);
#ifndef WIN32
    args.push_back("-S" + JoinPath(out_path_, "run_ida.idc"));
#else
    args.push_back("-S\"" + JoinPath(out_path_, "run_ida.idc") + "\"");
#endif
    args.push_back(in_file);
    if (!SpawnProcess(args, true /* Wait */, &status_message)) {
      LOG(INFO) << "failed to spawn IDA export process: " << GetLastOsError();
      LOG(INFO) << status_message;
      return;
    }

    LOG(INFO) << StringPrintf("%.2f\t%" PRIu64 "\t%s", timer.elapsed(),
                              GetFileSize(in_file), file.c_str());
  } while (!g_wants_to_quit);
}

void CreateIdaScript(const std::string& out_path) {
  std::string path(JoinPath(out_path, "run_ida.idc"));
  std::ofstream file(path);
  if (!file) {
    throw std::runtime_error(
        ("Could not create idc script at \"" + out_path + "\"").c_str());
  }
  file << "#include <idc.idc>\n"
       << "static main()\n"
       << "{\n"
       << "\tBatch(0);\n"
       << "\tWait();\n"
       << "\tExit( 1 - RunPlugin(\"zynamics_binexport_" << kBinExportVersion
       << "\", 2 ));\n"
       << "}\n";
}

void DeleteIdaScript(const std::string& out_path) {
  std::string path(JoinPath(out_path, "run_ida.idc"));
  std::remove(path.c_str());
}

void ListFiles(const std::string& path) {
  std::vector<std::string> entries;
  GetDirectoryEntries(path, &entries);

  TUniqueFiles files;
  for (const auto& entry : entries) {
    const auto file_path(JoinPath(path, entry));
    if (IsDirectory(file_path)) {
      continue;
    }
    const auto extension(ToUpper(GetFileExtension(file_path)));
    if (extension != ".BINEXPORT") {
      continue;
    }
    std::ifstream file(file_path, std::ios_base::binary);
    BinExport2 proto;
    if (proto.ParseFromIstream(&file)) {
      const auto& meta_information = proto.meta_information();
      LOG(INFO) << meta_information.executable_id() << " ("
                << meta_information.executable_name() << ")";
      continue;
    }
  }
}

void BatchDiff(const std::string& path, const std::string& reference_file,
               const std::string& out_path) {
  // Collect idb files to diff.
  std::vector<std::string> entries;
  GetDirectoryEntries(path, &entries);
  TUniqueFiles idb_files;
  TUniqueFiles diff_files;
  for (const auto& entry : entries) {
    auto file_path(JoinPath(path, entry));
    if (IsDirectory(file_path)) {
      continue;
    }
    // Export all idbs in directory.
    const auto extension(ToUpper(GetFileExtension(file_path)));
    if (extension == ".IDB" || extension == ".I64") {
      if (GetFileSize(file_path) > 0) {
        idb_files.insert(Basename(file_path));
      } else {
        LOG(INFO) << "Warning: skipping empty file " << file_path;
      }
    } else if (extension == ".BINEXPORT") {
      diff_files.insert(Basename(file_path));
    }
  }

  // TODO(soerenme): Remove all idbs that have already been exported from export
  //                 todo list.
  diff_files.insert(idb_files.begin(), idb_files.end());

  // Create todo list of file pairs.
  TFiles files;
  for (auto i = diff_files.cbegin(), end = diff_files.cend(); i != end; ++i) {
    for (auto j = diff_files.cbegin(); j != end; ++j) {
      if (i != j && (reference_file.empty() || reference_file == *i)) {
        files.emplace_back(*i, *j);
      }
    }
  }

  const size_t num_idbs = idb_files.size();
  const size_t num_diffs = files.size();
  const unsigned num_hardware_threads = std::thread::hardware_concurrency();
  XmlConfig config(XmlConfig::GetDefaultFilename(), "BinDiffDeluxe");
  const unsigned num_threads =
      config.ReadInt("/BinDiffDeluxe/Threads/@use", num_hardware_threads);
  const std::string ida_dir =
      config.ReadString("/BinDiffDeluxe/Ida/@directory", "");
  const std::string ida_exe =
      config.ReadString("/BinDiffDeluxe/Ida/@executable", "");
  const std::string ida_exe64 =
      config.ReadString("/BinDiffDeluxe/Ida/@executable64", "");
  Timer<> timer;
  {  // Export
    if (!idb_files.empty()) {
      CreateIdaScript(out_path);
    }
    std::vector<std::thread> threads;
    for (unsigned i = 0; i < num_threads; ++i) {
      threads.emplace_back(ExporterThread(path, out_path, ida_dir, ida_exe,
                                          ida_exe64, &idb_files));
    }
    for (auto& thread : threads) {
      thread.join();
    }
  }
  const auto export_time = timer.elapsed();
  timer.restart();

  if (!FLAGS_export) {  // Perform diff
    std::vector<std::thread> threads;
    for (unsigned i = 0; i < num_threads; ++i) {
      threads.emplace_back(DifferThread(out_path, out_path, &files));
    }
    for (auto& thread : threads) {
      thread.join();
    }
  }
  const auto diff_time = timer.elapsed();
  DeleteIdaScript(out_path);

  LOG(INFO) << StringPrintf(
      "%" PRIuMAX " files exported in %2f seconds, %" PRIuMAX
      " pairs diffed in %2f seconds",
      num_idbs, export_time, num_diffs * (1 - FLAGS_export), diff_time);
}

void DumpMdIndices(const CallGraph& call_graph, const FlowGraphs& flow_graphs) {
  std::cout << "\n"
            << call_graph.GetFilename() << "\n"
            << call_graph.GetMdIndex();
  for (auto i = flow_graphs.cbegin(), end = flow_graphs.cend(); i != end; ++i) {
    std::cout << "\n"
              << std::hex << std::setfill('0') << std::setw(16)
              << (*i)->GetEntryPointAddress() << "\t" << std::fixed
              << std::setprecision(12) << (*i)->GetMdIndex() << "\t"
              << ((*i)->IsLibrary() ? "Library" : "Non-library");
  }
  std::cout << std::endl;
}

void BatchDumpMdIndices(const std::string& path) {
  std::vector<std::string> entries;
  GetDirectoryEntries(path, &entries);
  for (const auto& entry : entries) {
    auto file_path(JoinPath(path, entry));
    if (IsDirectory(file_path)) {
      continue;
    }
    auto extension(ToUpper(GetFileExtension(file_path)));
    if (extension != ".CALL_GRAPH") {
      continue;
    }

    CallGraph call_graph;
    FlowGraphs flow_graphs;
    Instruction::Cache instruction_cache;
    ScopedCleanup cleanup(&flow_graphs, 0, &instruction_cache);
    FlowGraphInfos infos;
    Read(file_path, &call_graph, &flow_graphs, &infos, &instruction_cache);
    DumpMdIndices(call_graph, flow_graphs);
  }
}

void SignalHandler(int code) {
  static int signal_count = 0;
  switch (code) {
#ifdef WIN32
    case SIGBREAK:  // Ctrl-Break, not available on Unix
#endif
    case SIGINT:  // Ctrl-C
      if (++signal_count < 3) {
        LOG(INFO)
            << "Gracefully shutting down after current operations finish.";
        g_wants_to_quit = true;
      } else {
        LOG(INFO) << "Forcefully terminating process.";
        exit(1);
      }
      break;
  }
}

int main(int argc, char** argv) {
#ifdef WIN32
  signal(SIGBREAK, SignalHandler);
#endif
  signal(SIGINT, SignalHandler);

  const std::string current_path(GetCurrentDirectory());
  SetCommandLineOptionWithMode("output_dir", current_path.c_str(),
                               SET_FLAGS_DEFAULT);

  int exit_code = 0;
  try {
    std::string binary_name(Basename(argv[0]));
    std::string usage(
        "Finds similarities in binary code.\n"
        "Usage:\n");
    usage +=
        "  " + binary_name +
        " --primary=PRIMARY [--secondary=SECONDARY]\n\n"
        "Example command line to diff all files in a directory against each"
        " other:\n" +
        "  " + binary_name +
        " \\\n"
        "    --primary=/tmp --output_dir=/tmp/result\n"
        "Note that if the directory contains IDA Pro databases these will \n"
        "automatically be exported first.\n"
        "For a single diff:\n" +
        "  " + binary_name +
        " \\\n"
        "    --primary=/tmp/file1.BinExport "
        "--secondary=/tmp/file2.BinExport \\\n"
        "    --output_dir=/tmp/result";
#ifdef GOOGLE
    InitGoogle(usage.c_str(), &argc, &argv, true /* Remove flags */);
#else
    SetUsageMessage(usage);
    ParseCommandLineFlags(&argc, &argv, true /* Remove flags */);
#endif

    LOG(INFO) << kProgramVersion
#ifdef _DEBUG
              << ", debug build"
#endif
              << ", (c)2004-2011 zynamics GmbH, (c)2011-2017 Google Inc.";

    const auto user_app_data =
        GetDirectory(PATH_APPDATA, "BinDiff", /* create = */ false) +
        "bindiff.xml";
    const auto common_app_data =
        GetDirectory(PATH_COMMONAPPDATA, "BinDiff", /* create = */ false) +
        "bindiff.xml";
    if (!FLAGS_config.empty()) {
      XmlConfig::SetDefaultFilename(FLAGS_config);
    } else if (FileExists(user_app_data)) {
      XmlConfig::SetDefaultFilename(user_app_data);
    } else if (FileExists(common_app_data)) {
      XmlConfig::SetDefaultFilename(common_app_data);
    }
    const XmlConfig& config(GetConfig());
    if (!config.GetDocument()) {
      throw std::runtime_error("config file invalid or not found");
    }
    // This initializes static variables before the threads get to them.
    if (GetDefaultMatchingSteps().empty() ||
        GetDefaultMatchingStepsBasicBlock().empty()) {
      throw std::runtime_error("config file invalid");
    }

#ifndef GOOGLE
    // Echo original command line to log file, the internal version does this in
    // InitGoogle().
    LOG(INFO) << "Command line arguments:";
    for (int i = 0; i < argc; ++i) {
      LOG(INFO) << "argv[" << i << "]: '" << *(argv + i) << "'";
    }
#endif

    Timer<> timer;
    bool done_something = false;

    std::unique_ptr<CallGraph> call_graph1;
    std::unique_ptr<CallGraph> call_graph2;
    Instruction::Cache instruction_cache;
    FlowGraphs flow_graphs1;
    FlowGraphs flow_graphs2;
    ScopedCleanup cleanup(&flow_graphs1, &flow_graphs2, &instruction_cache);

    if (FLAGS_primary.empty()) {
      throw std::runtime_error("Need primary input (--primary)");
    }

    if (FLAGS_output_dir == current_path /* Defaulted */ &&
        IsDirectory(FLAGS_primary.c_str())) {
      FLAGS_output_dir = FLAGS_primary;
    }

    if (!IsDirectory(FLAGS_output_dir.c_str())) {
      throw std::runtime_error(
          "Output parameter (--output_dir) must be a writable directory! "
          "Supplied value: \"" +
          FLAGS_output_dir + "\"");
    }

    if (FileExists(FLAGS_primary.c_str())) {
      // Primary from file system.
      FlowGraphInfos infos;
      call_graph1.reset(new CallGraph());
      Read(FLAGS_primary, call_graph1.get(), &flow_graphs1, &infos,
           &instruction_cache);
    }

    if (IsDirectory(FLAGS_primary.c_str())) {
      // File system batch diff.
      if (FLAGS_ls) {
        ListFiles(FLAGS_primary);
      } else if (FLAGS_md_index) {
        BatchDumpMdIndices(FLAGS_primary);
      } else {
        BatchDiff(FLAGS_primary, FLAGS_secondary, FLAGS_output_dir);
      }
      done_something = true;
    }

    if (FLAGS_md_index && call_graph1 != nullptr) {
      DumpMdIndices(*call_graph1, flow_graphs1);
      done_something = true;
    }

    if (!FLAGS_secondary.empty() &&
        FileExists(FLAGS_secondary.c_str())) {
      // secondary from filesystem
      FlowGraphInfos infos;
      call_graph2.reset(new CallGraph());
      Read(FLAGS_secondary, call_graph2.get(), &flow_graphs2, &infos,
           &instruction_cache);
    }

    if (!done_something && ((!FileExists(FLAGS_primary.c_str()) &&
                             !IsDirectory(FLAGS_primary.c_str())) ||
                            (!FLAGS_secondary.empty() &&
                             (!FileExists(FLAGS_secondary.c_str()) &&
                              !IsDirectory(FLAGS_secondary.c_str()))))) {
      throw std::runtime_error(
          "Invalid inputs. Please make sure --primary and --secondary "
          "point to valid files/directories.");
    }

    if (call_graph1.get() && call_graph2.get()) {
      const int edges1 = num_edges(call_graph1->GetGraph());
      const int vertices1 = num_vertices(call_graph1->GetGraph());
      const int edges2 = num_edges(call_graph2->GetGraph());
      const int vertices2 = num_vertices(call_graph2->GetGraph());
      LOG(INFO) << "setup: " << timer.elapsed() << " sec. "
                << call_graph1->GetFilename() << " has " << vertices1
                << " functions and " << edges1 << " calls. "
                << call_graph2->GetFilename() << " has " << vertices2
                << " functions and " << edges2 << " calls.";
      timer.restart();

      const MatchingSteps default_callgraph_steps(GetDefaultMatchingSteps());
      const MatchingStepsFlowGraph default_basicblock_steps(
          GetDefaultMatchingStepsBasicBlock());
      FixedPoints fixed_points;
      MatchingContext context(*call_graph1, *call_graph2, flow_graphs1,
                              flow_graphs2, fixed_points);
      Diff(&context, default_callgraph_steps, default_basicblock_steps);

      Histogram histogram;
      Counts counts;
      GetCountsAndHistogram(flow_graphs1, flow_graphs2, fixed_points,
                            &histogram, &counts);
      Confidences confidences;
      const double confidence = GetConfidence(histogram, &confidences);
      const double similarity =
          GetSimilarityScore(*call_graph1, *call_graph2, histogram, counts);

      LOG(INFO) << "matching: " << timer.elapsed() << " sec.";
      timer.restart();

      LOG(INFO) << "matched " << fixed_points.size() << " of "
                << flow_graphs1.size() << "/" << flow_graphs2.size() << " ("
                << counts.find("functions primary (non-library)")->second << "/"
                << counts.find("functions secondary (non-library)")->second
                << ")";
      LOG(INFO) << StringPrintf(
          "call_graph1 MD index %16f\tcall_graph2 MD index %16f",
          call_graph1->GetMdIndex(), call_graph2->GetMdIndex());
      LOG(INFO) << StringPrintf("similarity: %5.4f%%\tconfidence: %5.4f%%",
                                similarity * 100.0, confidence * 100.0);

      ChainWriter writer;
      if (FLAGS_log_format) {
        writer.Add(std::make_shared<ResultsLogWriter>(GetTruncatedFilename(
            FLAGS_output_dir + "/", call_graph1->GetFilename(), "_vs_",
            call_graph2->GetFilename(), ".results")));
      }
      if (FLAGS_bin_format || writer.IsEmpty()) {
        writer.Add(std::make_shared<DatabaseWriter>(GetTruncatedFilename(
            FLAGS_output_dir + "/", call_graph1->GetFilename(), "_vs_",
            call_graph2->GetFilename(), ".BinDiff")));
      }

      if (!writer.IsEmpty()) {
        writer.Write(*call_graph1, *call_graph2, flow_graphs1, flow_graphs2,
                     fixed_points);
        LOG(INFO) << StringPrintf("writing results: %.3f sec.",
                                  timer.elapsed());
      }
      timer.restart();
      done_something = true;
    }

    if (!done_something) {
      ShowUsageWithFlags(argv[0]);
    }
  } catch (const std::exception& error) {
    LOG(INFO) << "an error occurred: " << error.what();
    exit_code = 1;
  } catch (...) {
    LOG(INFO) << "an unknown error occurred";
    exit_code = 2;
  }

  return exit_code;
}
