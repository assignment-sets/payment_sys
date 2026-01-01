import React, { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import axios from "axios";
import { transferSchema } from "../schemas/transferSchema";
import { toCents } from "../utils/moneyUtils";
import Button from "./ui/Button";
import MoneyInput from "./ui/MoneyInput";
import ErrorDisplay from "./ui/ErrorDisplay";
import { Send, CheckCircle2 } from "lucide-react";

const TransferMoney = () => {
  const [serverError, setServerError] = useState({ message: "", status: null });
  const [successData, setSuccessData] = useState(null);

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(transferSchema),
    defaultValues: {
      idempotencyKey: crypto.randomUUID(),
    },
  });

  const displayAmount = watch("displayAmount");

  const onSubmit = async (data) => {
    setServerError({ message: "", status: null });
    setSuccessData(null);

    try {
      const payload = {
        fromAccountId: parseInt(data.fromAccountId),
        toAccountId: parseInt(data.toAccountId),
        amount: toCents(data.displayAmount),
        idempotencyKey: data.idempotencyKey,
      };

      const response = await axios.post(
        "http://localhost:8080/api/transfers",
        payload
      );

      setSuccessData(response.data);
      // Reset form with a fresh idempotency key for the next potential transaction
      reset({
        fromAccountId: data.fromAccountId,
        toAccountId: "",
        displayAmount: "",
        idempotencyKey: crypto.randomUUID(),
      });
    } catch (error) {
      setServerError({
        message: error.response?.data?.error || "Transfer processing failed",
        status: error.response?.status,
      });
    }
  };

  return (
    <div className="max-w-lg mx-auto p-8 bg-white rounded-2xl shadow-xl border border-gray-100">
      <div className="flex items-center gap-2 mb-6">
        <Send className="text-blue-600 w-6 h-6" />
        <h2 className="text-2xl font-bold">Transfer Funds</h2>
      </div>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="text-sm font-medium text-gray-700">
              Source Account ID
            </label>
            <input
              type="number"
              {...register("fromAccountId", { valueAsNumber: true })}
              className="w-full p-2 border border-gray-300 rounded-md mt-1 focus:ring-2 focus:ring-blue-500 outline-none"
              placeholder="e.g. 1"
            />
            {errors.fromAccountId && (
              <p className="text-xs text-red-500 mt-1 font-medium">
                {errors.fromAccountId.message}
              </p>
            )}
          </div>
          <div>
            <label className="text-sm font-medium text-gray-700">
              Target Account ID
            </label>
            <input
              type="number"
              {...register("toAccountId", { valueAsNumber: true })}
              className="w-full p-2 border border-gray-300 rounded-md mt-1 focus:ring-2 focus:ring-blue-500 outline-none"
              placeholder="e.g. 2"
            />
            {errors.toAccountId && (
              <p className="text-xs text-red-500 mt-1 font-medium">
                {errors.toAccountId.message}
              </p>
            )}
          </div>
        </div>

        <MoneyInput
          value={displayAmount}
          onChange={(val) =>
            setValue("displayAmount", val, { shouldValidate: true })
          }
          label="Amount to Send"
        />
        {errors.displayAmount && (
          <p className="text-xs text-red-500 font-medium">
            {errors.displayAmount.message}
          </p>
        )}

        <Button
          title="Execute Transfer"
          type="submit"
          isLoading={isSubmitting}
          className="w-full py-3"
        />
      </form>

      {/* Success Notification */}
      {successData && (
        <div className="mt-6 p-4 bg-green-50 border border-green-200 rounded-lg animate-in fade-in slide-in-from-top-2">
          <div className="flex items-center gap-2 text-green-800 font-bold mb-1">
            <CheckCircle2 className="w-5 h-5" />
            <span>Transfer Successful!</span>
          </div>
          <div className="text-sm text-green-700 space-y-1">
            <p>
              <span className="font-medium">Transaction ID:</span>{" "}
              {successData.transactionId}
            </p>
            <p>
              <span className="font-medium">Status:</span> {successData.status}
            </p>
          </div>
        </div>
      )}

      {/* Standardized Error Notification */}
      <ErrorDisplay message={serverError.message} status={serverError.status} />
    </div>
  );
};

export default TransferMoney;
