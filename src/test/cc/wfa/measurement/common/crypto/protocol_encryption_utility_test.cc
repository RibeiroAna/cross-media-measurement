// Copyright 2020 The Measurement System Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "wfa/measurement/common/crypto/protocol_encryption_utility.h"

#include <openssl/obj_mac.h>

#include "crypto/commutative_elgamal.h"
#include "crypto/ec_commutative_cipher.h"
#include "gmock/gmock.h"
#include "google/protobuf/util/message_differencer.h"
#include "gtest/gtest.h"
#include "src/main/cc/any_sketch/crypto/sketch_encrypter.h"
#include "wfa/measurement/api/v1alpha/sketch.pb.h"
#include "wfa/measurement/common/crypto/protocol_encryption_methods.pb.h"

namespace wfa::measurement::common::crypto {
namespace {

using ::private_join_and_compute::CommutativeElGamal;
using ::private_join_and_compute::Context;
using ::private_join_and_compute::ECCommutativeCipher;
using ::private_join_and_compute::ECGroup;
using ::private_join_and_compute::ECPoint;
using ::private_join_and_compute::Status;
using ::private_join_and_compute::StatusCode;
using ::testing::SizeIs;
using ::wfa::measurement::api::v1alpha::Sketch;
using ::wfa::measurement::api::v1alpha::SketchConfig;
using FlagCount = DecryptLastLayerFlagAndCountResponse::FlagCount;

constexpr int kMaxFrequency = 10;
constexpr int kTestCurveId = NID_X9_62_prime256v1;
constexpr int kBytesPerEcPoint = 33;
constexpr int kBytesCipherText = kBytesPerEcPoint * 2;
constexpr int kBytesPerEncryptedRegister = kBytesCipherText * 3;

MATCHER_P2(StatusIs, code, message, "") {
  return ExplainMatchResult(
      AllOf(testing::Property(&Status::code, code),
            testing::Property(&Status::message, testing::HasSubstr(message))),
      arg, result_listener);
}

MATCHER_P(IsBlockSorted, block_size, "") {
  if (arg.length() % block_size != 0) {
    return false;
  }
  for (size_t i = block_size; i < arg.length(); i += block_size) {
    if (arg.substr(i, block_size) < arg.substr(i - block_size, block_size)) {
      return false;
    }
  }
  return true;
}

// Returns true if two proto messages are equal when ignoring the order of
// repeated fields.
MATCHER_P(EqualsProto, expected, "") {
  ::google::protobuf::util::MessageDifferencer differencer;
  differencer.set_repeated_field_comparison(
      ::google::protobuf::util::MessageDifferencer::AS_SET);
  return differencer.Compare(arg, expected);
}

ElGamalKeys GenerateRandomElGamalKeys(const int curve_id) {
  ElGamalKeys el_gamal_keys;
  auto el_gamal_cipher =
      CommutativeElGamal::CreateWithNewKeyPair(curve_id).value();
  *el_gamal_keys.mutable_el_gamal_sk() =
      el_gamal_cipher.get()->GetPrivateKeyBytes().value();
  *el_gamal_keys.mutable_el_gamal_pk()->mutable_el_gamal_g() =
      el_gamal_cipher.get()->GetPublicKeyBytes().value().first;
  *el_gamal_keys.mutable_el_gamal_pk()->mutable_el_gamal_y() =
      el_gamal_cipher.get()->GetPublicKeyBytes().value().second;
  return el_gamal_keys;
}

std::string GenerateRandomPhKey(const int curve_id) {
  auto p_h_cipher = ECCommutativeCipher::CreateWithNewKey(
                        curve_id, ECCommutativeCipher::HashType::SHA256)
                        .value();
  return p_h_cipher.get()->GetPrivateKeyBytes();
}

void AddRegister(Sketch* sketch, const int index, const int key,
                 const int count) {
  auto register_ptr = sketch->add_registers();
  register_ptr->set_index(index);
  register_ptr->add_values(key);
  register_ptr->add_values(count);
}

Sketch CreateEmptyClceSketch() {
  Sketch plain_sketch;
  plain_sketch.mutable_config()->add_values()->set_aggregator(
      SketchConfig::ValueSpec::UNIQUE);
  plain_sketch.mutable_config()->add_values()->set_aggregator(
      SketchConfig::ValueSpec::SUM);
  return plain_sketch;
}

FlagCount CreateFlagCount(const bool is_not_destroyed, const int frequency) {
  FlagCount result;
  result.set_is_not_destroyed(is_not_destroyed);
  result.set_frequency(frequency);
  return result;
}

// The TestData generates cipher keys for 3 duchies, and the combined public
// key for the data providers.
class TestData {
 public:
  ElGamalKeys duchy_1_el_gamal_keys_;
  std::string duchy_1_p_h_key_;
  ElGamalKeys duchy_2_el_gamal_keys_;
  std::string duchy_2_p_h_key_;
  ElGamalKeys duchy_3_el_gamal_keys_;
  std::string duchy_3_p_h_key_;
  ElGamalPublicKeys client_el_gamal_keys_;  // combined from 3 duchy keys;
  std::unique_ptr<any_sketch::crypto::SketchEncrypter> sketch_encrypter;

  TestData() {
    duchy_1_el_gamal_keys_ = GenerateRandomElGamalKeys(kTestCurveId);
    duchy_2_el_gamal_keys_ = GenerateRandomElGamalKeys(kTestCurveId);
    duchy_3_el_gamal_keys_ = GenerateRandomElGamalKeys(kTestCurveId);
    duchy_1_p_h_key_ = GenerateRandomPhKey(kTestCurveId);
    duchy_2_p_h_key_ = GenerateRandomPhKey(kTestCurveId);
    duchy_3_p_h_key_ = GenerateRandomPhKey(kTestCurveId);

    // Combine the el_gamal keys from all duchies to generate the data provider
    // el_gamal key.
    Context ctx;
    ECGroup ec_group = ECGroup::Create(kTestCurveId, &ctx).value();
    ECPoint duchy_1_public_el_gamal_y_ec =
        ec_group
            .CreateECPoint(duchy_1_el_gamal_keys_.el_gamal_pk().el_gamal_y())
            .value();
    ECPoint duchy_2_public_el_gamal_y_ec =
        ec_group
            .CreateECPoint(duchy_2_el_gamal_keys_.el_gamal_pk().el_gamal_y())
            .value();
    ECPoint duchy_3_public_el_gamal_y_ec =
        ec_group
            .CreateECPoint(duchy_3_el_gamal_keys_.el_gamal_pk().el_gamal_y())
            .value();
    ECPoint client_public_el_gamal_y_ec =
        duchy_1_public_el_gamal_y_ec.Add(duchy_2_public_el_gamal_y_ec)
            .value()
            .Add(duchy_3_public_el_gamal_y_ec)
            .value();
    client_el_gamal_keys_.set_el_gamal_g(
        duchy_1_el_gamal_keys_.el_gamal_pk().el_gamal_g());
    client_el_gamal_keys_.set_el_gamal_y(
        client_public_el_gamal_y_ec.ToBytesCompressed().value());

    any_sketch::crypto::CiphertextString client_public_key = {
        .u = client_el_gamal_keys_.el_gamal_g(),
        .e = client_el_gamal_keys_.el_gamal_y(),
    };

    // Create a sketch_encryter for encrypting plaintext any_sketch data.
    sketch_encrypter = any_sketch::crypto::CreateWithPublicKey(
                           kTestCurveId, kMaxFrequency, client_public_key)
                           .value();
  }

  // Helper function to go through the entire MPC protocol using the input data.
  // The final (flag, count) lists are returned.
  DecryptLastLayerFlagAndCountResponse GoThroughEntireMpcProtocol(
      const std::string& encrypted_sketch) {
    // Duchy 1 add noise to the sketch
    AddNoiseToSketchRequest add_noise_to_sketch_request;
    *add_noise_to_sketch_request.mutable_sketch() = encrypted_sketch;
    AddNoiseToSketchResponse add_noise_to_sketch_response =
        AddNoiseToSketch(add_noise_to_sketch_request).value();
    EXPECT_THAT(add_noise_to_sketch_response.sketch(),
                IsBlockSorted(kBytesPerEncryptedRegister));

    // Blind register indexes at duchy 1
    BlindOneLayerRegisterIndexRequest blind_one_layer_register_index_request_1;
    *blind_one_layer_register_index_request_1
         .mutable_local_pohlig_hellman_sk() = duchy_1_p_h_key_;
    *blind_one_layer_register_index_request_1.mutable_local_el_gamal_keys() =
        duchy_1_el_gamal_keys_;
    *blind_one_layer_register_index_request_1
         .mutable_composite_el_gamal_keys() = client_el_gamal_keys_;
    blind_one_layer_register_index_request_1.set_curve_id(kTestCurveId);
    *blind_one_layer_register_index_request_1.mutable_sketch() =
        add_noise_to_sketch_response.sketch();
    BlindOneLayerRegisterIndexResponse
        blind_one_layer_register_index_response_1 =
            BlindOneLayerRegisterIndex(blind_one_layer_register_index_request_1)
                .value();
    EXPECT_THAT(blind_one_layer_register_index_response_1.sketch(),
                IsBlockSorted(kBytesPerEncryptedRegister));

    // Blind register indexes at duchy 2
    BlindOneLayerRegisterIndexRequest blind_one_layer_register_index_request_2;
    *blind_one_layer_register_index_request_2
         .mutable_local_pohlig_hellman_sk() = duchy_2_p_h_key_;
    *blind_one_layer_register_index_request_2.mutable_local_el_gamal_keys() =
        duchy_2_el_gamal_keys_;
    *blind_one_layer_register_index_request_2
         .mutable_composite_el_gamal_keys() = client_el_gamal_keys_;
    blind_one_layer_register_index_request_2.set_curve_id(kTestCurveId);
    blind_one_layer_register_index_request_2.mutable_sketch()->append(
        blind_one_layer_register_index_response_1.sketch().begin(),
        blind_one_layer_register_index_response_1.sketch().end());
    BlindOneLayerRegisterIndexResponse
        blind_one_layer_register_index_response_2 =
            BlindOneLayerRegisterIndex(blind_one_layer_register_index_request_2)
                .value();
    EXPECT_THAT(blind_one_layer_register_index_response_2.sketch(),
                IsBlockSorted(kBytesPerEncryptedRegister));

    // Blind register indexes and join registers at duchy 3 (primary duchy)
    BlindLastLayerIndexThenJoinRegistersRequest
        blind_last_layer_index_then_join_registers_request;
    *blind_last_layer_index_then_join_registers_request
         .mutable_local_pohlig_hellman_sk() = duchy_3_p_h_key_;
    *blind_last_layer_index_then_join_registers_request
         .mutable_local_el_gamal_keys() = duchy_3_el_gamal_keys_;
    *blind_last_layer_index_then_join_registers_request
         .mutable_composite_el_gamal_keys() = client_el_gamal_keys_;
    blind_last_layer_index_then_join_registers_request.set_curve_id(
        kTestCurveId);
    blind_last_layer_index_then_join_registers_request.mutable_sketch()->append(
        blind_one_layer_register_index_response_2.sketch().begin(),
        blind_one_layer_register_index_response_2.sketch().end());
    BlindLastLayerIndexThenJoinRegistersResponse
        blind_last_layer_index_then_join_registers_response =
            BlindLastLayerIndexThenJoinRegisters(
                blind_last_layer_index_then_join_registers_request)
                .value();
    EXPECT_THAT(
        blind_last_layer_index_then_join_registers_response.flag_counts(),
        IsBlockSorted(kBytesCipherText * 2));

    // Decrypt flags and counts at duchy 1
    DecryptOneLayerFlagAndCountRequest
        decrypt_one_layer_flag_and_count_request_1;
    *decrypt_one_layer_flag_and_count_request_1.mutable_local_el_gamal_keys() =
        duchy_1_el_gamal_keys_;
    decrypt_one_layer_flag_and_count_request_1.set_curve_id(kTestCurveId);
    decrypt_one_layer_flag_and_count_request_1.mutable_flag_counts()->append(
        blind_last_layer_index_then_join_registers_response.flag_counts()
            .begin(),
        blind_last_layer_index_then_join_registers_response.flag_counts()
            .end());
    DecryptOneLayerFlagAndCountResponse
        decrypt_one_layer_flag_and_count_response_1 =
            DecryptOneLayerFlagAndCount(
                decrypt_one_layer_flag_and_count_request_1)
                .value();
    EXPECT_THAT(decrypt_one_layer_flag_and_count_response_1.flag_counts(),
                IsBlockSorted(kBytesCipherText * 2));

    // Decrypt flags and counts at duchy 2
    DecryptOneLayerFlagAndCountRequest
        decrypt_one_layer_flag_and_count_request_2;
    *decrypt_one_layer_flag_and_count_request_2.mutable_local_el_gamal_keys() =
        duchy_2_el_gamal_keys_;
    decrypt_one_layer_flag_and_count_request_2.set_curve_id(kTestCurveId);
    decrypt_one_layer_flag_and_count_request_2.mutable_flag_counts()->append(
        decrypt_one_layer_flag_and_count_response_1.flag_counts().begin(),
        decrypt_one_layer_flag_and_count_response_1.flag_counts().end());
    DecryptOneLayerFlagAndCountResponse
        decrypt_one_layer_flag_and_count_response_2 =
            DecryptOneLayerFlagAndCount(
                decrypt_one_layer_flag_and_count_request_2)
                .value();
    EXPECT_THAT(decrypt_one_layer_flag_and_count_request_2.flag_counts(),
                IsBlockSorted(kBytesCipherText * 2));

    // Decrypt flags and counts at duchy 3 (primary duchy).
    DecryptLastLayerFlagAndCountRequest
        decrypt_last_layer_flag_and_count_request;
    *decrypt_last_layer_flag_and_count_request.mutable_local_el_gamal_keys() =
        duchy_3_el_gamal_keys_;
    decrypt_last_layer_flag_and_count_request.set_curve_id(kTestCurveId);
    decrypt_last_layer_flag_and_count_request.set_maximum_frequency(
        kMaxFrequency);
    decrypt_last_layer_flag_and_count_request.mutable_flag_counts()->append(
        decrypt_one_layer_flag_and_count_response_2.flag_counts().begin(),
        decrypt_one_layer_flag_and_count_response_2.flag_counts().end());
    DecryptLastLayerFlagAndCountResponse final_response =
        DecryptLastLayerFlagAndCount(decrypt_last_layer_flag_and_count_request)
            .value();
    return final_response;
  }
};

TEST(BlindOneLayerRegisterIndex, keyAndCountShouldBeReRandomized) {
  TestData test_data;
  Sketch plain_sketch = CreateEmptyClceSketch();
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 111, /* count = */ 2);
  std::string encrypted_sketch =
      test_data.sketch_encrypter->Encrypt(plain_sketch).value();

  // Blind register indexes at duchy 1
  BlindOneLayerRegisterIndexRequest request;
  *request.mutable_local_pohlig_hellman_sk() = test_data.duchy_1_p_h_key_;
  *request.mutable_local_el_gamal_keys() = test_data.duchy_1_el_gamal_keys_;
  *request.mutable_composite_el_gamal_keys() = test_data.client_el_gamal_keys_;
  request.set_curve_id(kTestCurveId);
  request.mutable_sketch()->append(encrypted_sketch.begin(),
                                   encrypted_sketch.end());
  StatusOr<BlindOneLayerRegisterIndexResponse> response_1 =
      BlindOneLayerRegisterIndex(request);
  StatusOr<BlindOneLayerRegisterIndexResponse> response_2 =
      BlindOneLayerRegisterIndex(request);

  ASSERT_TRUE(response_1.ok());
  ASSERT_TRUE(response_2.ok());
  std::string raw_sketch = request.sketch();
  std::string blinded_sketch_1 = response_1.value().sketch();
  std::string blinded_sketch_2 = response_2.value().sketch();
  ASSERT_THAT(blinded_sketch_1, SizeIs(66 * 3));  // 1 register, 3 ciphertexts
  ASSERT_THAT(blinded_sketch_2, SizeIs(66 * 3));  // 1 register, 3 ciphertexts
  // Position changed due to blinding.
  ASSERT_NE(raw_sketch.substr(0, 66), blinded_sketch_1.substr(0, 66));
  ASSERT_NE(raw_sketch.substr(0, 66), blinded_sketch_2.substr(0, 66));
  // Key changed due to re-randomizing.
  ASSERT_NE(raw_sketch.substr(66, 66), blinded_sketch_1.substr(66, 66));
  ASSERT_NE(raw_sketch.substr(66, 66), blinded_sketch_2.substr(66, 66));
  // Count changed due to re-randomizing.
  ASSERT_NE(raw_sketch.substr(66 * 2, 66), blinded_sketch_1.substr(66 * 2, 66));
  ASSERT_NE(raw_sketch.substr(66 * 2, 66), blinded_sketch_2.substr(66 * 2, 66));
  // multiple re-randomizing results should be different.
  ASSERT_NE(blinded_sketch_1.substr(66, 66),
            blinded_sketch_2.substr(66, 66));  // Key
  ASSERT_NE(blinded_sketch_1.substr(66 * 2, 66),
            blinded_sketch_2.substr(66 * 2, 66));  // Count
}

TEST(BlindOneLayerRegisterIndex, WrongInputSketchSizeShouldThrow) {
  BlindOneLayerRegisterIndexRequest request;
  auto result = BlindOneLayerRegisterIndex(request);

  ASSERT_FALSE(result.ok());
  EXPECT_THAT(result.status(), StatusIs(StatusCode::kInvalidArgument, "empty"));

  *request.mutable_sketch() = "1234";
  result = BlindOneLayerRegisterIndex(request);
  ASSERT_FALSE(result.ok());
  EXPECT_THAT(result.status(),
              StatusIs(StatusCode::kInvalidArgument, "not divisible"));
}

TEST(BlindLastLayerIndexThenJoinRegisters, WrongInputSketchSizeShouldThrow) {
  BlindLastLayerIndexThenJoinRegistersRequest request;
  auto result = BlindLastLayerIndexThenJoinRegisters(request);
  ASSERT_FALSE(result.ok());
  EXPECT_THAT(result.status(), StatusIs(StatusCode::kInvalidArgument, "empty"));

  *request.mutable_sketch() = "1234";
  result = BlindLastLayerIndexThenJoinRegisters(request);
  ASSERT_FALSE(result.ok());
  EXPECT_THAT(result.status(),
              StatusIs(StatusCode::kInvalidArgument, "not divisible"));
}

TEST(DecryptOneLayerFlagAndCount, WrongInputSketchSizeShouldThrow) {
  DecryptOneLayerFlagAndCountRequest request;
  auto result = DecryptOneLayerFlagAndCount(request);
  ASSERT_FALSE(result.ok());
  EXPECT_THAT(result.status(), StatusIs(StatusCode::kInvalidArgument, "empty"));

  *request.mutable_flag_counts() = "1234";
  result = DecryptOneLayerFlagAndCount(request);
  ASSERT_FALSE(result.ok());
  EXPECT_THAT(result.status(),
              StatusIs(StatusCode::kInvalidArgument, "not divisible"));
}

TEST(DecryptLastLayerFlagAndCount, WrongInputSketchSizeShouldThrow) {
  DecryptLastLayerFlagAndCountRequest request;
  auto result = DecryptLastLayerFlagAndCount(request);
  ASSERT_FALSE(result.ok());
  EXPECT_THAT(result.status(), StatusIs(StatusCode::kInvalidArgument, "empty"));

  *request.mutable_flag_counts() = "1234";
  result = DecryptLastLayerFlagAndCount(request);
  ASSERT_FALSE(result.ok());
  EXPECT_THAT(result.status(),
              StatusIs(StatusCode::kInvalidArgument, "not divisible"));
}

TEST(EndToEnd, SumOfCountsShouldBeCorrect) {
  TestData test_data;
  Sketch plain_sketch = CreateEmptyClceSketch();
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 111, /* count = */ 2);
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 111, /* count = */ 3);
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 111, /* count = */ 4);
  std::string encrypted_sketch =
      test_data.sketch_encrypter->Encrypt(plain_sketch).value();

  DecryptLastLayerFlagAndCountResponse final_response =
      test_data.GoThroughEntireMpcProtocol(encrypted_sketch);

  DecryptLastLayerFlagAndCountResponse expected;
  *expected.add_flag_counts() = CreateFlagCount(true, 9);

  EXPECT_GE(final_response.elapsed_cpu_time_millis(), 0);
  final_response.clear_elapsed_cpu_time_millis();
  EXPECT_THAT(final_response, EqualsProto(expected));
}

TEST(EndToEnd, SumOfCoutsShouldBeCappedByMaximumFrequency) {
  TestData test_data;
  Sketch plain_sketch = CreateEmptyClceSketch();
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 111,
              /* count = */ kMaxFrequency - 2);
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 111, /* count = */ 3);
  std::string encrypted_sketch =
      test_data.sketch_encrypter->Encrypt(plain_sketch).value();

  DecryptLastLayerFlagAndCountResponse final_response =
      test_data.GoThroughEntireMpcProtocol(encrypted_sketch);

  DecryptLastLayerFlagAndCountResponse expected;
  *expected.add_flag_counts() = CreateFlagCount(true, kMaxFrequency);

  EXPECT_GE(final_response.elapsed_cpu_time_millis(), 0);
  final_response.clear_elapsed_cpu_time_millis();
  EXPECT_THAT(final_response, EqualsProto(expected));
}

TEST(EndToEnd, KeyCollisionShouldDestroyCount) {
  TestData test_data;
  Sketch plain_sketch = CreateEmptyClceSketch();
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 111, /* count = */ 2);
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 222, /* count = */ 2);
  std::string encrypted_sketch =
      test_data.sketch_encrypter->Encrypt(plain_sketch).value();

  DecryptLastLayerFlagAndCountResponse final_response =
      test_data.GoThroughEntireMpcProtocol(encrypted_sketch);

  DecryptLastLayerFlagAndCountResponse expected;
  *expected.add_flag_counts() = CreateFlagCount(false, kMaxFrequency);

  EXPECT_GE(final_response.elapsed_cpu_time_millis(), 0);
  final_response.clear_elapsed_cpu_time_millis();
  EXPECT_THAT(final_response, EqualsProto(expected));
}

TEST(EndToEnd, ZeroCountShouldBeSkipped) {
  TestData test_data;
  Sketch plain_sketch = CreateEmptyClceSketch();
  AddRegister(&plain_sketch, /* index = */ 2, /* key = */ 222, /* count = */ 0);
  std::string encrypted_sketch =
      test_data.sketch_encrypter->Encrypt(plain_sketch).value();

  DecryptLastLayerFlagAndCountResponse final_response =
      test_data.GoThroughEntireMpcProtocol(encrypted_sketch);

  DecryptLastLayerFlagAndCountResponse expected;

  EXPECT_GE(final_response.elapsed_cpu_time_millis(), 0);
  final_response.clear_elapsed_cpu_time_millis();
  EXPECT_THAT(final_response, EqualsProto(expected));
}

TEST(EndToEnd, CombinedCases) {
  TestData test_data;
  Sketch plain_sketch = CreateEmptyClceSketch();

  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 100, /* count = */ 1);
  AddRegister(&plain_sketch, /* index = */ 2, /* key = */ 200, /* count = */ 2);
  AddRegister(&plain_sketch, /* index = */ 3, /* key = */ 300, /* count = */ 3);
  AddRegister(&plain_sketch, /* index = */ 4, /* key = */ 400, /* count = */ 4);
  AddRegister(&plain_sketch, /* index = */ 5, /* key = */ 500, /* count = */ 0);
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 101, /* count = */ 3);
  AddRegister(&plain_sketch, /* index = */ 1, /* key = */ 102, /* count = */ 1);
  AddRegister(&plain_sketch, /* index = */ 2, /* key = */ 200, /* count = */ 3);
  AddRegister(&plain_sketch, /* index = */ 3, /* key = */ 300, /* count = */ 8);
  AddRegister(&plain_sketch, /* index = */ 4, /* key = */ 400, /* count = */ 0);

  std::string encrypted_sketch =
      test_data.sketch_encrypter->Encrypt(plain_sketch).value();

  DecryptLastLayerFlagAndCountResponse final_response =
      test_data.GoThroughEntireMpcProtocol(encrypted_sketch);

  DecryptLastLayerFlagAndCountResponse expected;
  // For index 1, key collisions, the counter is destroyed ( decrypted as
  // kMaxFrequency)
  *expected.add_flag_counts() = CreateFlagCount(false, kMaxFrequency);
  // For index 2, no key collision, count sum = 2 + 3 = 5;
  *expected.add_flag_counts() = CreateFlagCount(true, 5);
  // For index 3, no key collision, count sum = 3 + 8 = 11 > 10 (max_frequency)
  *expected.add_flag_counts() = CreateFlagCount(true, kMaxFrequency);
  // For index 4, no key collision, count sum = 4 + 0 = 4;
  *expected.add_flag_counts() = CreateFlagCount(true, 4);
  // For index 5, no key collision, count sum = 0; ignored in the result.

  EXPECT_GE(final_response.elapsed_cpu_time_millis(), 0);
  final_response.clear_elapsed_cpu_time_millis();
  EXPECT_THAT(final_response, EqualsProto(expected));
}

}  // namespace
}  // namespace wfa::measurement::common::crypto